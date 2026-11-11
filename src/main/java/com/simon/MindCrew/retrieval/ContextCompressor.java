package com.simon.MindCrew.retrieval;

import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 上下文压缩器
 * 去除冗余切片，将超长内容截断，保留与 query 最相关的核心信息
 *
 * 压缩策略：
 *   1. 分数过滤：rerankScore 低于 rag.min_rerank_score 的切片直接丢弃
 *   2. 去重：内容前 50 个字符相同的切片视为重复，保留 rerankScore 更高的那条
 *   3. 同知识库去重：同一 KB 保留最多 rag.max_chunks_per_kb 条
 *   4. 截断：单条切片内容超过上限时截断
 *   5. 总量控制：保留结果集总字符数不超过上限
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContextCompressor {

    private final AiConfigHolder aiConfigHolder;

    /** 每条切片默认最大字符数 */
    private static final int DEFAULT_CHUNK_MAX_CHARS = 800;

    /**
     * 压缩切片列表
     *
     * @param chunks    原始切片（已按 rerankScore 降序）
     * @param query     用户问题（保留用于后续扩展关键词高亮等逻辑）
     * @param maxTokens 目标 Token 上限（估算：1 token ≈ 2 中文字符）
     * @return 压缩去重后的切片列表
     */
    public List<RetrievedChunk> compress(List<RetrievedChunk> chunks,
                                          String query,
                                          int maxTokens) {
        if (chunks == null || chunks.isEmpty()) {
            return new ArrayList<>();
        }

        int maxTotalChars = maxTokens * 2; // 粗估

        // ---- Step 1: 分数过滤 ----
        List<RetrievedChunk> filtered = filterByScore(chunks);

        // ---- Step 2: 去重（内容前50字相同视为重复）----
        List<RetrievedChunk> deduplicated = deduplicate(filtered);

        // ---- Step 3: 同知识库去重（每 KB 最多 MAX_CHUNKS_PER_KB 条）----
        List<RetrievedChunk> kbDeduped = deduplicateByKb(deduplicated);

        // ---- Step 4: 截断单条超长切片 ----
        List<RetrievedChunk> truncated = truncateChunks(kbDeduped, DEFAULT_CHUNK_MAX_CHARS);

        // ---- Step 5: 按总字符数控制 ----
        List<RetrievedChunk> result = limitByTotalChars(truncated, maxTotalChars);

        log.info("[ContextCompressor] 原始={}条 → 分数过滤={}条 → 去重={}条 → KB去重={}条 → 截断后={}条 → 最终={}条",
                chunks.size(), filtered.size(), deduplicated.size(), kbDeduped.size(), truncated.size(), result.size());

        return result;
    }

    // ==================== 私有方法 ====================

    /** 过滤掉 rerankScore 过低的切片 */
    private List<RetrievedChunk> filterByScore(List<RetrievedChunk> chunks) {
        float minScore = safeGetFloat("rag.min_rerank_score", 0.15f);
        List<RetrievedChunk> result = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            float score = chunk.getRerankScore() > 0 ? chunk.getRerankScore() : chunk.getScore();
            if (score >= minScore) {
                result.add(chunk);
            }
        }
        // 至少保留 1 条（即使分数很低），避免完全空结果
        if (result.isEmpty() && !chunks.isEmpty()) {
            result.add(chunks.get(0));
        }
        return result;
    }

    /** 同一知识库最多保留 N 条，优先保留高分切片。
     *  单 KB 场景下不做限制，让截断和总量控制处理；多 KB 场景下才做限制以保证多样性。 */
    private List<RetrievedChunk> deduplicateByKb(List<RetrievedChunk> chunks) {
        // 统计候选集中不同 KB 的数量
        long distinctKbCount = chunks.stream()
                .map(RetrievedChunk::getKnowledgeBaseId)
                .filter(Objects::nonNull)
                .distinct()
                .count();

        // 单 KB：无需 per-KB 限制，交由截断和总量控制处理
        if (distinctKbCount <= 1) {
            return new ArrayList<>(chunks);
        }

        // 多 KB：强制 per-KB 上限，防止一个大 KB 挤占其他 KB 的空间
        int maxPerKb = safeGetInt("rag.max_chunks_per_kb", 5);
        List<RetrievedChunk> result = new ArrayList<>();
        java.util.Map<Long, Integer> kbCounts = new java.util.HashMap<>();

        for (RetrievedChunk chunk : chunks) {
            Long kbId = chunk.getKnowledgeBaseId();
            if (kbId == null) {
                result.add(chunk);
                continue;
            }
            int count = kbCounts.getOrDefault(kbId, 0);
            if (count < maxPerKb) {
                result.add(chunk);
                kbCounts.put(kbId, count + 1);
            }
        }
        return result;
    }

    private float safeGetFloat(String key, float defaultValue) {
        try { return aiConfigHolder.getFloat(key); }
        catch (Exception e) { return defaultValue; }
    }

    private int safeGetInt(String key, int defaultValue) {
        try { return aiConfigHolder.getInt(key); }
        catch (Exception e) { return defaultValue; }
    }

    private List<RetrievedChunk> deduplicate(List<RetrievedChunk> chunks) {
        List<RetrievedChunk> result = new ArrayList<>();
        Set<String> seenPrefixes = new LinkedHashSet<>();

        for (RetrievedChunk chunk : chunks) {
            String content = chunk.getContent();
            if (content == null || content.isBlank()) continue;

            String prefix = content.substring(0, Math.min(50, content.length())).trim();
            if (!seenPrefixes.contains(prefix)) {
                seenPrefixes.add(prefix);
                result.add(chunk);
            } else {
                log.debug("[ContextCompressor] 去重切片: prefix='{}'", prefix);
            }
        }
        return result;
    }

    private List<RetrievedChunk> truncateChunks(List<RetrievedChunk> chunks, int maxCharsPerChunk) {
        List<RetrievedChunk> result = new ArrayList<>();
        for (RetrievedChunk chunk : chunks) {
            String content = chunk.getContent();
            if (content != null && content.length() > maxCharsPerChunk) {
                // 创建新对象，避免修改原始数据
                RetrievedChunk truncated = new RetrievedChunk();
                truncated.setId(chunk.getId());
                truncated.setContent(content.substring(0, maxCharsPerChunk) + "…");
                truncated.setScore(chunk.getScore());
                truncated.setCategory(chunk.getCategory());
                truncated.setContentType(chunk.getContentType());
                truncated.setChapter(chunk.getChapter());
                truncated.setPageNumber(chunk.getPageNumber());
                truncated.setKnowledgeBaseId(chunk.getKnowledgeBaseId());
                truncated.setSourceName(chunk.getSourceName());
                truncated.setSourceRef(chunk.getSourceRef());
                truncated.setSource(chunk.getSource());
                truncated.setRrfRank(chunk.getRrfRank());
                truncated.setRerankScore(chunk.getRerankScore());
                result.add(truncated);
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    private List<RetrievedChunk> limitByTotalChars(List<RetrievedChunk> chunks, int maxTotalChars) {
        List<RetrievedChunk> result = new ArrayList<>();
        int totalChars = 0;
        for (RetrievedChunk chunk : chunks) {
            int len = chunk.getContent() != null ? chunk.getContent().length() : 0;
            if (totalChars + len > maxTotalChars) {
                break;
            }
            result.add(chunk);
            totalChars += len;
        }
        return result;
    }
}
