package com.simon.MindCrew.crew.agents;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.crew.dto.Finding;
import com.simon.MindCrew.crew.dto.PlanItem;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.simon.MindCrew.retrieval.ContextCompressor;
import com.simon.MindCrew.service.rag.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 调研员 Agent。
 *
 * 对单个 Planner 子任务执行：
 *  1. 多路召回（Vector + BM25）
 *  2. RRF 融合
 *  3. Cross-Encoder 重排
 *  4. 上下文压缩
 *  5. LLM 提炼要点（不生成最终答案，只输出"调研发现"）
 *
 * 复用现有 RAG 流水线，但跳过持久化和 SSE 流式输出环节，
 * 适合在并行 / 长任务场景下调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResearcherAgent {

    private final VectorRetriever       vectorRetriever;
    private final BM25Retriever         bm25Retriever;
    private final RRFFusion             rrfFusion;
    private final CrossEncoderReranker  crossEncoderReranker;
    private final ContextCompressor     contextCompressor;
    private final AiConfigHolder        aiConfigHolder;
    private final KbKnowledgeBaseMapper kbKnowledgeBaseMapper;
    private final KbChunkMapper kbChunkMapper;

    private static final String SYSTEM_PROMPT = """
            你是 MindCrew 的「调研员」Agent，负责对给定的子主题进行深度信息提炼。

            工作要求：
              1. 仅基于提供的「参考资料」整理事实，不要编造
              2. 输出结构化的调研要点（3-6 条），每条 1-2 句话
              3. 关键事实后用 [N] 标注来源编号
              4. 资料不足时直接说"资料中未发现 …"，不要硬凑

            输出 Markdown 格式：
              **关键发现**
              - 要点 1 [1]
              - 要点 2 [2][3]
              ...

              **小结**：一句话总结本子主题的核心结论。
            """;

    private static final int VECTOR_TOPK = 12;
    private static final int BM25_TOPK   = 12;
    private static final int FUSED_TOPN  = 16;
    private static final int RERANK_TOPK = 6;

    /**
     * 执行单个调研任务。
     *
     * @param item       Planner 拆出的子任务
     * @param kbIds      检索范围
     * @return Finding 含要点摘要和来源引用
     */
    public Finding research(PlanItem item, List<Long> kbIds) {
        long t0 = System.currentTimeMillis();
        log.info("[ResearcherAgent] start research subtask #{}: {}", item.getIndex(), item.getQuery());

        String query = item.getQuery();
        if (query == null || query.isBlank()) {
            return new Finding(item.getIndex(), item.getTitle(), item.getSection(),
                    "资料中未发现可用信息。", List.of());
        }

        // ── 1. 多路召回 ────────────────────────────
        List<RetrievedChunk> vectorHits;
        List<RetrievedChunk> bm25Hits;
        try {
            vectorHits = vectorRetriever.retrieve(query, null, kbIds, VECTOR_TOPK);
        } catch (Exception e) {
            log.warn("[ResearcherAgent] vector retrieve failed: {}", e.getMessage());
            vectorHits = List.of();
        }
        try {
            bm25Hits = bm25Retriever.retrieve(query, null, kbIds, BM25_TOPK);
        } catch (Exception e) {
            log.warn("[ResearcherAgent] bm25 retrieve failed: {}", e.getMessage());
            bm25Hits = List.of();
        }

        // ── 2. RRF 融合 ────────────────────────────
        List<RetrievedChunk> fused = rrfFusion.fuse(vectorHits, bm25Hits, FUSED_TOPN);

        if (fused.isEmpty()) {
            log.info("[ResearcherAgent] no candidates for subtask #{}", item.getIndex());
            return new Finding(item.getIndex(), item.getTitle(), item.getSection(),
                    "资料中未发现与 \"" + item.getTitle() + "\" 相关的内容。", List.of());
        }

        // ── 3. 重排 ────────────────────────────────
        List<RetrievedChunk> reranked;
        try {
            reranked = crossEncoderReranker.rerank(query, fused, RERANK_TOPK);
        } catch (Exception e) {
            log.warn("[ResearcherAgent] rerank failed: {}, fallback to fused list", e.getMessage());
            reranked = fused.size() > RERANK_TOPK ? fused.subList(0, RERANK_TOPK) : fused;
        }

        // ── 4. 上下文压缩 ──────────────────────────
        List<RetrievedChunk> compressed;
        try {
            compressed = contextCompressor.compress(reranked, query, 1800);
        } catch (Exception e) {
            log.warn("[ResearcherAgent] compress failed: {}", e.getMessage());
            compressed = reranked;
        }

        // ── 4.5 补全文档名（knowledgeBaseId → sourceName）─────
        enrichSourceNames(compressed);

        // ── 5. LLM 提炼要点 ────────────────────────
        String contextText = buildContextText(compressed);
        String summary;
        try {
            ChatClient client = ChatClient.builder(aiConfigHolder.getChatModel())
                    .defaultSystem(SYSTEM_PROMPT)
                    .build();

            summary = client.prompt()
                    .user("""
                          子主题：%s
                          调研问题：%s

                          参考资料：
                          %s
                          """.formatted(item.getTitle(), item.getQuery(), contextText))
                    .call()
                    .content();
        } catch (Exception e) {
            log.warn("[ResearcherAgent] LLM summary failed: {}", e.getMessage());
            summary = "调研过程中 LLM 调用失败：" + e.getMessage();
        }

        // ── 6. 组装 Finding ───────────────────────
        List<Finding.SourceRef> sources = buildSources(compressed);

        log.info("[ResearcherAgent] subtask #{} done in {}ms, {} sources",
                item.getIndex(), System.currentTimeMillis() - t0, sources.size());

        return new Finding(item.getIndex(), item.getTitle(), item.getSection(),
                summary, sources);
    }

    /** 构造给 LLM 看的上下文文本（带 [N] 编号） */
    private String buildContextText(List<RetrievedChunk> chunks) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk c = chunks.get(i);
            sb.append("[").append(i + 1).append("] ")
              .append("《").append(safeName(c.getSourceName())).append("》");
            if (c.getChapter() != null && !c.getChapter().isBlank()) {
                sb.append(" — ").append(c.getChapter());
            }
            if (c.getPageNumber() > 0) {
                sb.append(" P.").append(c.getPageNumber());
            }
            sb.append("\n").append(c.getContent()).append("\n\n");
        }
        return sb.toString();
    }

    /** 转换成 SourceRef，用于报告引用（含时间戳级溯源元数据） */
    private List<Finding.SourceRef> buildSources(List<RetrievedChunk> chunks) {
        List<Finding.SourceRef> refs = new ArrayList<>();
        for (RetrievedChunk c : chunks) {
            String excerpt = c.getContent();
            if (excerpt != null && excerpt.length() > 160) {
                excerpt = excerpt.substring(0, 160) + "…";
            }
            Finding.SourceRef ref = new Finding.SourceRef();
            ref.setDocName(safeName(c.getSourceName()));
            ref.setChapter(c.getChapter());
            ref.setPageNumber(c.getPageNumber() > 0 ? c.getPageNumber() : null);
            ref.setExcerpt(excerpt);
            ref.setScore((double) c.getRerankScore());
            // 溯源元数据
            ref.setMediaType(c.getMediaType());
            ref.setKnowledgeBaseId(c.getKnowledgeBaseId());
            ref.setStartMs(c.getStartMs());
            ref.setEndMs(c.getEndMs());
            ref.setSpeakerId(c.getSpeakerId());
            // sourceUrl 此处先不生成（在 Controller 层按需生成预签名 URL，避免 Agent 持有 storage 依赖）
            refs.add(ref);
        }
        return refs;
    }

    /**
     * 批量补全 chunk 的溯源元数据：
     *  - sourceName  ← kb_knowledge_base.name
     *  - mediaType   ← kb_knowledge_base.file_type 推断 + chunk metadata
     *  - startMs/endMs/speakerId/sourceObjectName  ← 回查 kb_chunk.metadata JSON
     *
     * 通过 (kbId, content 前 100 字符) 匹配 kb_chunk 行（在同一 kbId 下 content 前缀唯一性极高）。
     */
    private void enrichSourceNames(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;

        Set<Long> kbIds = chunks.stream()
                .map(RetrievedChunk::getKnowledgeBaseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (kbIds.isEmpty()) return;

        try {
            // 1. 查 kb_knowledge_base 拿名称 + 文件类型
            List<KbKnowledgeBase> kbList = kbKnowledgeBaseMapper.selectList(
                    new LambdaQueryWrapper<KbKnowledgeBase>()
                            .in(KbKnowledgeBase::getId, kbIds)
                            .select(KbKnowledgeBase::getId, KbKnowledgeBase::getName, KbKnowledgeBase::getFileType)
            );
            Map<Long, KbKnowledgeBase> kbMap = kbList.stream()
                    .collect(Collectors.toMap(KbKnowledgeBase::getId, kb -> kb));

            // 2. 查 kb_chunk 批量拿 metadata（按 kb_id 批量）
            List<KbChunk> dbChunks = kbChunkMapper.selectList(
                    new LambdaQueryWrapper<KbChunk>()
                            .in(KbChunk::getKbId, kbIds)
                            .select(KbChunk::getKbId, KbChunk::getContent, KbChunk::getMetadata)
            );
            // (kbId + content 前 100 字符) → metadata JSON
            Map<String, String> metaMap = new HashMap<>();
            for (KbChunk db : dbChunks) {
                String key = db.getKbId() + "::" + prefix(db.getContent(), 100);
                if (db.getMetadata() != null) metaMap.put(key, db.getMetadata());
            }

            // 3. 给每个 RetrievedChunk 补全
            chunks.forEach(c -> {
                KbKnowledgeBase kb = kbMap.get(c.getKnowledgeBaseId());
                if (kb != null) {
                    if (c.getSourceName() == null || c.getSourceName().isBlank()) {
                        c.setSourceName(kb.getName() != null ? kb.getName() : "知识库文档");
                    }
                    // 根据文件类型推断 mediaType
                    if (c.getMediaType() == null) {
                        c.setMediaType(inferMediaTypeByFileType(kb.getFileType()));
                    }
                }

                // 从 metadata JSON 补全时间戳 + sourceObjectName
                if (c.getStartMs() == null) {
                    String key = c.getKnowledgeBaseId() + "::" + prefix(c.getContent(), 100);
                    String metaJson = metaMap.get(key);
                    if (metaJson != null) {
                        try {
                            JSONObject m = JSON.parseObject(metaJson);
                            if (c.getStartMs() == null) c.setStartMs(m.getLong("startMs"));
                            if (c.getEndMs() == null) c.setEndMs(m.getLong("endMs"));
                            if (c.getSpeakerId() == null) c.setSpeakerId(m.getString("speakerId"));
                            if (c.getSourceObjectName() == null) c.setSourceObjectName(m.getString("sourceObjectName"));
                        } catch (Exception ignored) {}
                    }
                }
            });
        } catch (Exception e) {
            log.warn("[ResearcherAgent] enrichSourceNames failed: {}", e.getMessage());
        }
    }

    private static String prefix(String text, int n) {
        if (text == null) return "";
        return text.length() <= n ? text : text.substring(0, n);
    }

    private static String inferMediaTypeByFileType(String fileType) {
        if (fileType == null) return "document";
        return switch (fileType.toLowerCase()) {
            case "pdf" -> "pdf";
            case "pptx", "ppt" -> "pptx";
            case "xlsx", "xls", "csv" -> "xlsx";
            case "jpg", "jpeg", "png", "webp", "bmp", "gif" -> "image";
            case "mp3", "wav", "m4a", "aac", "flac", "opus", "ogg", "amr" -> "audio";
            case "mp4", "mov", "mkv", "avi" -> "video";
            default -> "document";
        };
    }

    private String safeName(String name) {
        return (name == null || name.isBlank()) ? "知识库文档" : name;
    }
}
