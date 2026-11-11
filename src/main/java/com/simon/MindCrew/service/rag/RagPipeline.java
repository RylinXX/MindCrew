package com.simon.MindCrew.service.rag;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.MedConversation;
import com.simon.MindCrew.entity.MedKnowledgeBase;
import com.simon.MindCrew.entity.MedMessage;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.MedConversationMapper;
import com.simon.MindCrew.mapper.MedKnowledgeBaseMapper;
import com.simon.MindCrew.mapper.MedMessageMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 核心流水线
 * 统一调度：Query改写 → 多路召回 → RRF融合 → Cross-Encoder重排序 → Prompt组装 → LLM流式生成 → 安全兜底
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagPipeline {

    private final QueryRewriter queryRewriter;
    private final VectorRetriever vectorRetriever;
    private final BM25Retriever bm25Retriever;
    private final RRFFusion rrfFusion;
    private final CrossEncoderReranker reranker;
    private final PromptAssembler promptAssembler;
    private final SafetyGuard safetyGuard;
    private final MedConversationMapper conversationMapper;
    private final MedMessageMapper messageMapper;
    private final MedKnowledgeBaseMapper knowledgeBaseMapper;
    private final RagCacheService ragCacheService;
    private final AiConfigHolder aiConfigHolder;

    /**
     * QaMessageMapper 双写（Phase 2 新表）
     * 使用 @org.springframework.beans.factory.annotation.Autowired(required = false) 保持向后兼容
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private QaMessageMapper qaMessageMapper;

    /** 缓存命中时每批发送的字符数（模拟流式输出） */
    private static final int CACHE_CHUNK_SIZE = 30;

    /**
     * 完整 RAG 流水线（SSE 流式输出）
     *
     * @param userId         当前用户ID
     * @param conversationId 会话ID（null则自动创建）
     * @param question       用户问题
     * @param userProfile    用户补充画像
     * @param emitter        SSE 发送器
     * @return 会话ID
     */
    public Long execute(Long userId, Long conversationId, String question,
                        String userProfile, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();

        // ① 获取/创建会话
        MedConversation conversation = getOrCreateConversation(userId, conversationId, question);

        // ② 先读取历史（在保存本条消息之前，避免把当前问题混入 history）
        String history = buildConversationHistory(conversation.getId());

        // ③ 保存用户消息
        saveMessage(conversation.getId(), "user", question, null, null);

        // ④ 归一化问题，自增频次
        String normalized = ragCacheService.normalize(question);
        long frequency = ragCacheService.incrementFrequency(normalized);
        log.info("[RAG Cache] question freq={} normalized={}", frequency, normalized);

        // ⑤ 频次 >= 阈值时检查缓存（首次及低频时跳过，避免缓存穿透）
        if (frequency >= aiConfigHolder.getInt("cache.freq_threshold")) {
            RagCachedResult cached = ragCacheService.getCache(normalized);
            if (cached != null) {
                // 命中缓存 → 模拟流式输出后直接返回
                replayFromCache(conversation, cached, startTime, emitter);
                return conversation.getId();
            }
        }

        // ⑥ 未命中缓存 → 执行完整 RAG 流水线
        Map<String, Object> retrievalLog = new LinkedHashMap<>();
        retrievalLog.put("originalQuery", question);

        try {
            // ===== Step 1: 高风险情形检测 =====
            boolean isEmergency = safetyGuard.isEmergency(question);

            // ===== Step 2: Query 改写 =====
            String rewrittenQuery = queryRewriter.rewrite(question);
            retrievalLog.put("rewrittenQuery", rewrittenQuery);
            sendSseEvent(emitter, "rewrite", Map.of("original", question, "rewritten", rewrittenQuery));

            // ===== Step 3: 多路召回 =====
            int vectorTopK = aiConfigHolder.getInt("rag.vector_top_k");
            int bm25TopK   = aiConfigHolder.getInt("rag.bm25_top_k");
            int rrfTopN    = aiConfigHolder.getInt("rag.rrf_top_n");
            int rerankTopK = aiConfigHolder.getInt("rag.rerank_top_k");

            List<RetrievedChunk> vectorResults = vectorRetriever.retrieve(rewrittenQuery, null, vectorTopK);
            List<RetrievedChunk> bm25Results = bm25Retriever.retrieve(rewrittenQuery, null, bm25TopK);
            retrievalLog.put("vectorResults", vectorResults.size());
            retrievalLog.put("bm25Results", bm25Results.size());
            sendSseEvent(emitter, "retrieval", Map.of(
                    "vectorCount", vectorResults.size(),
                    "bm25Count", bm25Results.size()
            ));

            // ===== Step 4: RRF 融合 =====
            List<RetrievedChunk> fusedResults = rrfFusion.fuse(vectorResults, bm25Results, rrfTopN);
            retrievalLog.put("rrfCount", fusedResults.size());

            // ===== Step 5: Cross-Encoder 重排序 =====
            List<RetrievedChunk> topChunks = reranker.rerank(rewrittenQuery, fusedResults, rerankTopK);
            retrievalLog.put("rerankTop", topChunks.size());
            sendSseEvent(emitter, "rerank", Map.of("topK", topChunks.size()));

            // ===== Step 5.5: 批量补全文档名（knowledgeBaseId → sourceName）=====
            enrichSourceNames(topChunks);

            // ===== Step 6: 置信度评估 =====
            boolean needsFallback = safetyGuard.needsFallback(topChunks);
            retrievalLog.put("isFallback", needsFallback);

            // ===== Step 7: 组装 Prompt =====
            String prompt = needsFallback
                    ? promptAssembler.assembleFallback(question, history)
                    : promptAssembler.assemble(question, topChunks, userProfile, history);

            // ===== Step 8: LLM 流式生成 =====
            final StringBuilder answerBuilder = new StringBuilder();
            final List<Map<String, Object>> sources = buildSources(topChunks);
            sendSseEvent(emitter, "start", Map.of("message", "开始生成..."));

            // Spring AI Reactor 流式生成（executor 线程中阻塞等待完成）
            try {
                aiConfigHolder.getChatModel()
                        .stream(new org.springframework.ai.chat.prompt.Prompt(prompt))
                        .doOnNext(chatResponse -> {
                            String token = chatResponse.getResult().getOutput().getText();
                            if (token != null && !token.isEmpty()) {
                                answerBuilder.append(token);
                                sendSseEvent(emitter, "token", Map.of("content", token));
                            }
                        })
                        .blockLast();

                String answer = answerBuilder.toString();
                if (isEmergency)   answer += safetyGuard.getEmergencyWarning();
                if (needsFallback) answer += safetyGuard.getFallbackNotice();

                int elapsed = (int) (System.currentTimeMillis() - startTime);
                saveMessage(conversation.getId(), "assistant", answer,
                        JSON.toJSONString(sources), JSON.toJSONString(retrievalLog), elapsed);

                RagCachedResult cacheResult = new RagCachedResult(
                        answer, sources, needsFallback, isEmergency, rewrittenQuery, retrievalLog);
                ragCacheService.putCacheIfFrequent(normalized, cacheResult, frequency);

                Map<String, Object> donePayload = new LinkedHashMap<>();
                donePayload.put("sources", sources);
                donePayload.put("isFallback", needsFallback);
                donePayload.put("isEmergency", isEmergency);
                donePayload.put("responseTime", elapsed);
                donePayload.put("conversationId", conversation.getId());
                donePayload.put("retrievalLog", retrievalLog);
                sendSseEvent(emitter, "done", donePayload);
                emitter.complete();
                updateConversation(conversation);

            } catch (Exception streamError) {
                log.error("LLM 生成失败", streamError);
                sendSseEvent(emitter, "error", Map.of("message", "生成失败，请重试"));
                emitter.completeWithError(streamError);
            }

        } catch (Exception e) {
            log.error("RAG Pipeline 执行失败", e);
            sendSseEvent(emitter, "error", Map.of("message", "系统异常：" + e.getMessage()));
            emitter.completeWithError(e);
        }

        return conversation.getId();
    }

    /**
     * 从缓存结果模拟流式输出，避免再次执行 RAG 流水线
     */
    private void replayFromCache(MedConversation conversation, RagCachedResult cached,
                                  long startTime, SseEmitter emitter) {
        log.info("[RAG Cache] replaying cached answer for conversationId={}", conversation.getId());
        try {
            // 发送改写事件（来自缓存）
            sendSseEvent(emitter, "rewrite", Map.of(
                    "original", cached.getRetrievalLog().getOrDefault("originalQuery", ""),
                    "rewritten", cached.getRewrittenQuery() != null ? cached.getRewrittenQuery() : "",
                    "fromCache", true
            ));
            sendSseEvent(emitter, "start", Map.of("message", "开始生成（缓存）..."));

            // 分块发送 token，模拟流式效果
            String answer = cached.getAnswer();
            int len = answer.length();
            for (int i = 0; i < len; i += CACHE_CHUNK_SIZE) {
                String chunk = answer.substring(i, Math.min(i + CACHE_CHUNK_SIZE, len));
                sendSseEvent(emitter, "token", Map.of("content", chunk));
            }

            // 保存 AI 回答到数据库
            int elapsed = (int) (System.currentTimeMillis() - startTime);
            saveMessage(conversation.getId(), "assistant", answer,
                    JSON.toJSONString(cached.getSources()),
                    JSON.toJSONString(cached.getRetrievalLog()), elapsed);

            // 发送完成事件
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("sources", cached.getSources());
            donePayload.put("isFallback", cached.isFallback());
            donePayload.put("isEmergency", cached.isEmergency());
            donePayload.put("responseTime", elapsed);
            donePayload.put("conversationId", conversation.getId());
            donePayload.put("retrievalLog", cached.getRetrievalLog());
            donePayload.put("fromCache", true);
            sendSseEvent(emitter, "done", donePayload);

            emitter.complete();
            updateConversation(conversation);
        } catch (Exception e) {
            log.error("缓存回放失败", e);
            emitter.completeWithError(e);
        }
    }

    // ==================== 私有方法 ====================

    private MedConversation getOrCreateConversation(Long userId, Long conversationId, String question) {
        if (conversationId != null) {
            MedConversation existing = conversationMapper.selectById(conversationId);
            if (existing != null && existing.getUserId().equals(userId)) {
                return existing;
            }
        }
        // 创建新会话，标题取前20个字符
        MedConversation conv = new MedConversation();
        conv.setUserId(userId);
        conv.setTitle(question.length() > 20 ? question.substring(0, 20) + "..." : question);
        conv.setMessageCount(0);
        conv.setLastActive(LocalDateTime.now());
        conversationMapper.insert(conv);
        return conv;
    }

    private MedMessage saveMessage(Long conversationId, String role, String content,
                                    String sources, String retrievalLog) {
        return saveMessage(conversationId, role, content, sources, retrievalLog, null);
    }

    private MedMessage saveMessage(Long conversationId, String role, String content,
                                    String sources, String retrievalLog, Integer latencyMs) {
        // MedMessage 与 QaMessage 都映射 qa_message 表（MedMessage 是历史实体），
        // 因此这里只能写一次，统一用 QaMessage 落库并补齐检索日志/时延，避免同一消息重复入库。
        MedMessage msg = new MedMessage();
        msg.setConversationId(conversationId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setSources(sources);
        msg.setRetrievalLog(retrievalLog);
        msg.setFeedback(0);
        msg.setIsFallback(0);

        if (qaMessageMapper != null) {
            try {
                QaMessage qaMsg = new QaMessage();
                qaMsg.setConversationId(conversationId);
                qaMsg.setRole(role);
                qaMsg.setContent(content);
                qaMsg.setSources(sources);
                // agentTrace / mcpCalls / reflectionLog 由 MindCrewAgent 负责写入，此处为 null
                qaMsg.setAgentTrace(null);
                qaMsg.setMcpCalls(null);
                qaMsg.setReflectionLog(null);
                // 检索过程日志落库，供「检索分析」统计
                qaMsg.setRetrievalLog(retrievalLog);
                qaMsg.setFeedback(0);
                if (latencyMs != null) {
                    qaMsg.setLatencyMs(latencyMs);
                    qaMsg.setResponseTime(latencyMs);
                }
                qaMessageMapper.insert(qaMsg);
            } catch (Exception e) {
                log.warn("[RagPipeline] qa_message 写入失败（不影响主流程）: {}", e.getMessage());
            }
        } else {
            // 兼容兜底：qaMessageMapper 未注入时退回旧实体写入（同表）
            messageMapper.insert(msg);
        }

        return msg;
    }

    private void updateConversation(MedConversation conv) {
        conv.setMessageCount(conv.getMessageCount() + 2);
        conv.setLastActive(LocalDateTime.now());
        conversationMapper.updateById(conv);
    }

    private String buildConversationHistory(Long conversationId) {
        // 取最近6条消息（3轮对话）作为上下文
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MedMessage> wrapper =
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<MedMessage>()
                        .eq(MedMessage::getConversationId, conversationId)
                        .orderByDesc(MedMessage::getCreateTime)
                        .last("LIMIT 6");

        List<MedMessage> messages = messageMapper.selectList(wrapper);
        if (messages.isEmpty()) return "";

        // 反转为正序
        Collections.reverse(messages);

        return messages.stream()
                .map(m -> ("user".equals(m.getRole()) ? "用户" : "助手") + "：" + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    /**
     * 批量查询文档名，补全 chunk.sourceName
     */
    private void enrichSourceNames(List<RetrievedChunk> chunks) {
        Set<Long> kbIds = chunks.stream()
                .map(RetrievedChunk::getKnowledgeBaseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (kbIds.isEmpty()) return;

        List<MedKnowledgeBase> kbList = knowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<MedKnowledgeBase>()
                        .in(MedKnowledgeBase::getId, kbIds)
                        .select(MedKnowledgeBase::getId, MedKnowledgeBase::getName)
        );
        Map<Long, String> nameMap = kbList.stream()
                .collect(Collectors.toMap(MedKnowledgeBase::getId, MedKnowledgeBase::getName));

        chunks.forEach(chunk -> {
            if (chunk.getKnowledgeBaseId() != null) {
                chunk.setSourceName(nameMap.getOrDefault(chunk.getKnowledgeBaseId(), "知识库文档"));
            }
        });
    }

    private List<Map<String, Object>> buildSources(List<RetrievedChunk> chunks) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("index", i + 1);
            source.put("name", chunk.getSourceName() != null ? chunk.getSourceName() : "知识库文档");
            source.put("chapter", chunk.getChapter());
            source.put("pageNumber", chunk.getPageNumber());
            source.put("content", chunk.getContent().substring(0, Math.min(100, chunk.getContent().length())) + "...");
            source.put("score", chunk.getRerankScore());
            sources.add(source);
        }
        return sources;
    }

    private void sendSseEvent(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(JSON.toJSONString(data)));
        } catch (Exception e) {
            log.warn("SSE发送失败: {}", e.getMessage());
        }
    }
}
