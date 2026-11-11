package com.simon.MindCrew.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbChunk;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.KbChunkMapper;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.mcp.DocSearchTool;
import com.simon.MindCrew.mcp.KeywordSearchTool;
import com.simon.MindCrew.mcp.MemoryTool;
import com.simon.MindCrew.mcp.WebSearchTool;
import com.simon.MindCrew.retrieval.ContextCompressor;
import com.simon.MindCrew.service.knowledge.DocumentExtractor;
import com.simon.MindCrew.service.knowledge.TextChunker;
import com.simon.MindCrew.service.rag.*;
import com.simon.MindCrew.support.KbIdsParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * MindCrew ReAct Agent 核心执行器
 *
 * 执行流程（ReAct 模式）：
 *   1. 意图识别（QueryRouter）
 *   2. 根据意图选择工具（Tool Selection）
 *   3. 多路召回（VectorRetriever + BM25Retriever + WebSearch[可选]）
 *   4. RRF 融合
 *   5. Cross-Encoder 重排序
 *   6. 上下文压缩（ContextCompressor）
 *   7. LLM 流式生成
 *   8. 自纠错（SelfReflection，最多 MAX_REFLECTION_ROUNDS 轮）
 *   9. 持久化（qa_conversation + qa_message）+ SSE 输出
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MindCrewAgent {

    private static final int DEFAULT_DOCUMENT_SCOPE_CHUNK_BUDGET = 18;

    /** LLM 驱动检索阶段的 System Prompt */
    private static final String AGENT_RETRIEVAL_SYSTEM_PROMPT = """
            你是知识检索助手，负责调用工具收集与用户问题相关的信息。

            调用规则：
            - 默认调用 doc_search（语义检索），topK 设为 15，kbIds 留空
            - 条款/法规/编号/精确术语查询：同时调用 keyword_search，kbIds 留空
            - 问题涉及最新动态、时效性信息（含年份/近期/最新等词）：同时调用 web_search，maxResults 设为 5
            - 追问/个性化问题：先调用 recall_memory，userId 和 topic 留空
            - 不要生成最终答案，工具调用完成后只需回复"检索完成"
            """;

    // ==================== 依赖注入 ====================
    private final QueryRouter        queryRouter;
    private final SelectedDocumentScopeDecider selectedDocumentScopeDecider;
    private final ExplicitMemoryExtractor explicitMemoryExtractor;
    private final SelfReflection     selfReflection;
    private final VectorRetriever    vectorRetriever;
    private final BM25Retriever      bm25Retriever;
    private final RRFFusion          rrfFusion;
    private final CrossEncoderReranker reranker;
    private final QueryRewriter      queryRewriter;
    private final ContextCompressor  contextCompressor;
    private final AiConfigHolder     aiConfigHolder;
    private final PromptAssembler    promptAssembler;
    private final com.simon.MindCrew.service.PersonaService personaService;
    private final SafetyGuard        safetyGuard;
    private final RagCacheService    ragCacheService;
    private final SourcePayloadFactory sourcePayloadFactory;
    private final com.simon.MindCrew.service.QaGoldenPairService goldenPairService;
    private final com.simon.MindCrew.service.knowledge.FileStorageService fileStorage;
    private final com.simon.MindCrew.service.knowledge.VisionRecognizer visionRecognizer;
    private final com.simon.MindCrew.service.KbAclService kbAclService;
    private final com.simon.MindCrew.service.UsageStatsService usageStatsService;

    // MCP Tools
    private final DocSearchTool      docSearchTool;
    private final KeywordSearchTool  keywordSearchTool;
    private final WebSearchTool      webSearchTool;
    private final MemoryTool         memoryTool;
    private final ToolCallbackProvider toolCallbackProvider;
    private final DocumentExtractor  documentExtractor;
    private final TextChunker        textChunker;

    // Mappers
    private final KbChunkMapper         kbChunkMapper;
    private final QaConversationMapper  qaConversationMapper;
    private final QaMessageMapper       qaMessageMapper;
    private final KbKnowledgeBaseMapper kbKnowledgeBaseMapper;

    @Value("${upload.path:uploads}")
    private String uploadPath;

    /** 每批缓存命中模拟流式字符数 */
    private static final int CACHE_CHUNK_SIZE = 30;

    // ==================== 主入口 ====================

    /**
     * 执行 Agent 推理（SSE 流式输出）
     *
     * @param userId         用户 ID（字符串，兼容 Long 和 UUID）
     * @param conversationId 会话 ID（null 时自动创建）
     * @param question       用户问题
     * @param emitter        SSE 发射器
     * @return 会话 ID
     */
    public Long execute(String userId, Long conversationId, String question, List<Long> kbIds, SseEmitter emitter) {
        return execute(userId, conversationId, question, kbIds, List.of(), emitter);
    }

    /**
     * 执行 Agent 推理（含图片输入版本 · 任务 10 图片输入问答）
     *
     * @param imageObjectNames  用户上传的图片对象名（OSS/MinIO）；可空
     */
    public Long execute(String userId, Long conversationId, String question,
                        List<Long> kbIds, List<String> imageObjectNames, SseEmitter emitter) {
        long startTime = System.currentTimeMillis();
        AgentState state = new AgentState();
        state.setUserId(userId);
        state.setConversationId(conversationId);
        state.setQuery(question);

        // 任务 7 · ACL 过滤
        //  - 用户传 kbIds → 与用户可见集合取交集（防止越权指定别人的 KB）
        //  - 用户不传 → 用全部可见集合（自动全库检索）
        List<Long> userScope = resolveAccessibleKbIds(userId);
        List<Long> finalKbIds;
        if (kbIds != null && !kbIds.isEmpty()) {
            finalKbIds = new ArrayList<>(kbIds);
            if (!userScope.isEmpty()) finalKbIds.retainAll(userScope);
        } else {
            finalKbIds = new ArrayList<>(userScope);
        }
        state.setKbIds(finalKbIds);
        state.setExplicitKbScope(kbIds != null && !kbIds.isEmpty());
        log.info("[MindCrewAgent] ACL · userScope={} 指定={} 最终={}",
                userScope.size(), kbIds == null ? 0 : kbIds.size(), finalKbIds.size());

        // ① 获取或创建会话
        QaConversation conversation = getOrCreateConversation(userId, conversationId, question, kbIds);
        state.setConversationId(conversation.getId());

        // ②.0 处理图片输入（任务 10）· 走 VL 提取画面文字+描述，与 question 融合
        String effectiveQuery = question;
        String userMessageSources = null;
        if (imageObjectNames != null && !imageObjectNames.isEmpty()) {
            ImageAnalysisResult ia = analyzeImages(imageObjectNames, question, emitter);
            effectiveQuery = ia.augmentedQuery;
            userMessageSources = ia.sourcesJson;
        }

        // ② 保存用户消息（带图片来源）
        saveQaMessage(conversation.getId(), "user", question, userMessageSources, null, null, null);

        // ②.5 Golden Pair 短路 · 任务 6 校正反哺闭环核心
        //     人工校正过的标准答案优先返回，跳过完整 RAG，保证"已纠正过的问题永不再错"
        //     ⚠ 含图片的问题不走 Golden Pair（图片本身可能不同）
        if (imageObjectNames == null || imageObjectNames.isEmpty()) {
            if (tryGoldenPairShortCircuit(question, conversation, emitter, startTime)) {
                return conversation.getId();
            }
        }

        // 替换 question 为图片增强后的 query（供下游 RAG 用）
        state.setQuery(effectiveQuery);
        question = effectiveQuery;

        // ③ 归一化 & 缓存频次检查
        String normalized = ragCacheService.normalize(question);
        long frequency = ragCacheService.incrementFrequency(normalized);
        log.info("[MindCrewAgent] 开始处理 conversationId={} freq={}", conversation.getId(), frequency);

        // ④ 缓存命中 → 模拟流式回放
        int freqThreshold = safeGetInt("cache.freq_threshold", 3);
        if (frequency >= freqThreshold) {
            RagCachedResult cached = ragCacheService.getCache(normalized);
            if (cached != null) {
                replayFromCache(conversation, cached, startTime, emitter, state);
                return conversation.getId();
            }
        }

        // ⑤ 执行 ReAct 推理循环
        try {
            runReActLoop(state, conversation, startTime, emitter, normalized, frequency);
        } catch (Exception e) {
            log.error("[MindCrewAgent] 推理执行异常", e);
            sendSseEvent(emitter, "error", Map.of("message", "系统异常：" + e.getMessage()));
            emitter.completeWithError(e);
        }

        return conversation.getId();
    }

    // ==================== ReAct 推理循环 ====================

    private void runReActLoop(AgentState state,
                               QaConversation conversation,
                               long startTime,
                               SseEmitter emitter,
                               String normalized,
                               long frequency) {
        String question = state.getQuery();

        // ===== Thought 1：Query 改写 =====
        addTrace(state, 1, "改写查询，提升检索召回率",
                "QueryRewriter.rewrite", question, null);

        String rewrittenQuery = queryRewriter.rewrite(question);
        state.setRewrittenQuery(rewrittenQuery);

        updateTrace(state, 1, "改写完成：" + rewrittenQuery);
        sendSseEvent(emitter, "rewrite", Map.of(
                "original", question,
                "rewritten", rewrittenQuery
        ));

        // ===== Thought 2：文档直读 & 显式记忆写入 =====
        boolean documentScopedRetrieval = selectedDocumentScopeDecider.shouldDirectRead(
                state.getKbIds(), question, state.isExplicitKbScope());
        state.setDocumentScopedRetrieval(documentScopedRetrieval);

        Map<String, Object> explicitMemory = explicitMemoryExtractor.extract(question);
        if (!explicitMemory.isEmpty()) {
            long t0 = System.currentTimeMillis();
            Map<String, Object> storeResult = memoryTool.storeMemory(state.getUserId(), explicitMemory);
            state.getMemoryContext().putAll(explicitMemory);
            recordMcpCall(state, MemoryTool.STORE_TOOL_NAME,
                    Map.of("userId", state.getUserId(), "prefs", explicitMemory),
                    storeResult,
                    System.currentTimeMillis() - t0);
        }

        // ===== Thought 3：LLM 驱动工具选择 & 多路召回 =====
        addTrace(state, 3, "LLM 决策工具选择并执行多路检索",
                "ChatClient.toolCalling", rewrittenQuery, null);

        List<RetrievedChunk> allChunks;
        if (documentScopedRetrieval) {
            allChunks = retrieveSelectedDocumentChunks(state);
            if (allChunks.isEmpty()) {
                state.setDocumentScopedRetrieval(false);
                updateTrace(state, 3, "文档直读未提取到内容，回退到 LLM 驱动检索");
                allChunks = llmDrivenRetrieve(state, rewrittenQuery, emitter);
            }
        } else {
            allChunks = llmDrivenRetrieve(state, rewrittenQuery, emitter);
        }

        updateTrace(state, 3, "召回完成，共 " + allChunks.size() + " 条切片，工具：" + state.getSelectedTools());
        sendSseEvent(emitter, "retrieval", Map.of(
                "totalCount", allChunks.size(),
                "tools", state.getSelectedTools(),
                "mode", state.isDocumentScopedRetrieval() ? "selected_document" : "llm_driven"
        ));

        // ===== Thought 4：RRF 融合 + 重排序 =====
        addTrace(state, 4, "融合多路结果，重排序取 Top-K",
                "RRFFusion+Reranker", rewrittenQuery, null);

        int rrfTopN    = safeGetInt("rag.rrf_top_n", 15);
        int rerankTopK = safeGetInt("rag.rerank_top_k", 6);

        // 将 allChunks 拆回向量和 BM25 两路（简化处理：按 source 分组后传入 RRF）
        List<RetrievedChunk> vectorPart = allChunks.stream()
                .filter(c -> c.getSource() == RetrievedChunk.Source.VECTOR
                          || c.getSource() == RetrievedChunk.Source.HYBRID)
                .collect(Collectors.toList());
        List<RetrievedChunk> bm25Part = allChunks.stream()
                .filter(c -> c.getSource() == RetrievedChunk.Source.BM25)
                .collect(Collectors.toList());
        List<RetrievedChunk> webPart = allChunks.stream()
                .filter(c -> c.getSource() == RetrievedChunk.Source.WEB)
                .collect(Collectors.toList());

        List<RetrievedChunk> fused;
        List<RetrievedChunk> reranked;
        if (state.isDocumentScopedRetrieval()) {
            fused = new ArrayList<>(allChunks);
            reranked = assignSequentialScores(new ArrayList<>(allChunks));
        } else {
            fused = rrfFusion.fuse(vectorPart, bm25Part, rrfTopN);
            List<RetrievedChunk> rerankCandidates = mergeForRerank(fused, webPart);
            reranked = reranker.rerank(rewrittenQuery, rerankCandidates, rerankTopK);
        }

        // 补全文档名
        enrichSourceNames(reranked);

        // 上下文压缩
        int maxTokens = state.isDocumentScopedRetrieval()
                ? safeGetInt("rag.document_scope_max_tokens", 5000)
                : safeGetInt("rag.context_max_tokens", 3000);
        List<RetrievedChunk> compressed = contextCompressor.compress(reranked, rewrittenQuery, maxTokens);
        state.setRetrievedChunks(compressed);

        updateTrace(state, 4, "重排序后 " + reranked.size() + " 条，压缩后 " + compressed.size() + " 条");
        sendSseEvent(emitter, "rerank", Map.of("topK", reranked.size(), "compressed", compressed.size()));

        // ===== Thought 5：安全检查 + Prompt 组装 =====
        addTrace(state, 5, "安全评估 & 组装 Prompt",
                "SafetyGuard+PromptAssembler", question, null);

        boolean isEmergency = safetyGuard.isEmergency(question);
        boolean needsFallback = safetyGuard.needsFallback(compressed);
        String history = buildConversationHistory(conversation.getId());
        String basePrompt = needsFallback
                ? promptAssembler.assembleFallback(question, history)
                : promptAssembler.assemble(question, compressed, state.getMemoryContext(), null, history);

        // 注入 Soul 人格（含反讨好底线）到 prompt 顶部
        String personaPrompt = personaService.buildDefaultSystemPrompt();
        String prompt = personaPrompt.isBlank()
                ? basePrompt
                : personaPrompt + "\n\n━━━━━━━━━━━━━━━━━━━━━━\n\n" + basePrompt;

        updateTrace(state, 5, String.format("isEmergency=%b needsFallback=%b", isEmergency, needsFallback));

        // ===== Thought 6：LLM 生成 + 自纠错（ReAct Loop）=====
        addTrace(state, 6, "调用 LLM 流式生成答案",
                "LLM.streamingGenerate", prompt.substring(0, Math.min(100, prompt.length())) + "...", null);

        sendSseEvent(emitter, "start", Map.of("message", "开始生成..."));

        // 构建检索日志，供 done 事件使用
        Map<String, Object> retrievalLog = buildRetrievalLog(state, question, rewrittenQuery,
                vectorPart.size(), bm25Part.size(), webPart.size(), fused.size(), reranked.size());

        final StringBuilder answerBuilder = new StringBuilder();
        final List<Map<String, Object>> sources = sourcePayloadFactory.build(compressed);

        // ===== Spring AI Reactor 流式生成（在 executor 线程中 blockLast 阻塞） =====
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

            // ===== Self-Reflection 自纠错 =====
            String rawAnswer = answerBuilder.toString();
            String finalAnswer = runSelfReflection(state, question, compressed, rawAnswer, emitter);

            if (isEmergency)    finalAnswer += safetyGuard.getEmergencyWarning();
            if (needsFallback)  finalAnswer += safetyGuard.getFallbackNotice();

            state.setFinalAnswer(finalAnswer);
            updateTrace(state, 6, "生成完成，长度=" + finalAnswer.length());

            // 写入缓存
            RagCachedResult cacheResult = new RagCachedResult(
                    finalAnswer, sources, needsFallback, isEmergency, rewrittenQuery, retrievalLog);
            ragCacheService.putCacheIfFrequent(normalized, cacheResult, frequency);

            // 持久化（含 retrievalLog · 刷新/切换会话后仍能查检索过程）
            int elapsed = (int) (System.currentTimeMillis() - startTime);
            // 任务 13/14 · 同一条 assistant 消息写入模型、Token、成本、时延，供检索分析与成本核算
            String modelName = getActiveModelName();
            int inToks = estimateTokens(state.getRewrittenQuery())
                    + estimateTokens(buildConversationHistory(conversation.getId()));
            int outToks = estimateTokens(finalAnswer);
            java.math.BigDecimal costCny = java.math.BigDecimal.ZERO;
            try {
                costCny = usageStatsService.calcCost(modelName, inToks, outToks).getCost();
            } catch (Exception costEx) {
                log.warn("[MindCrewAgent] 成本估算失败（不影响主流程）: {}", costEx.getMessage());
            }
            saveQaMessage(conversation.getId(), "assistant", finalAnswer,
                    JSON.toJSONString(sources),
                    JSON.toJSONString(state.getAgentTrace()),
                    JSON.toJSONString(state.getMcpCalls()),
                    JSON.toJSONString(state.getReflectionLog()),
                    JSON.toJSONString(retrievalLog),
                    modelName, inToks, outToks, costCny, elapsed);
            updateConversation(conversation);

            // 任务 13 · 异步记账（不阻塞响应）
            try {
                Long uid = parseUserId(state.getUserId());
                if (uid != null) {
                    usageStatsService.recordChatAsync(uid, modelName, inToks, outToks, false);
                }
            } catch (Exception statsEx) {
                log.warn("[MindCrewAgent] 用量记账失败（不影响主流程）: {}", statsEx.getMessage());
            }

            // 发送完成事件
            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("sources", sources);
            donePayload.put("isFallback", needsFallback);
            donePayload.put("isEmergency", isEmergency);
            donePayload.put("responseTime", elapsed);
            donePayload.put("conversationId", conversation.getId());
            donePayload.put("retrievalLog", retrievalLog);
            donePayload.put("agentTrace", state.getAgentTrace());
            donePayload.put("reflectionPassed", state.isReflectionPassed());
            donePayload.put("intentType", state.getIntentType());
            sendSseEvent(emitter, "done", donePayload);
            emitter.complete();

        } catch (Exception error) {
            log.error("[MindCrewAgent] LLM生成失败", error);
            sendSseEvent(emitter, "error", Map.of("message", "生成失败：" + error.getMessage()));
            emitter.completeWithError(error);
        }
    }

    // ==================== LLM 驱动检索 ====================

    /**
     * 使用 ChatClient + ToolCallbackProvider 让 LLM 动态决定调用哪些工具、调用几次。
     * 工具执行时通过 AgentToolContext（ThreadLocal）收集 RetrievedChunk。
     * 若 LLM tool-calling 失败则降级为规则路由的 multiRetrieve。
     */
    private List<RetrievedChunk> llmDrivenRetrieve(AgentState state,
                                                    String rewrittenQuery,
                                                    SseEmitter emitter) {
        sendSseEvent(emitter, "thinking", Map.of(
                "step", "tool_selection",
                "message", "Agent 正在分析问题并选择检索工具..."
        ));

        AgentToolContext.activate(state.getKbIds(), state.getUserId());
        try {
            ChatClient agentClient = ChatClient.builder(aiConfigHolder.getChatModel())
                    .defaultSystem(AGENT_RETRIEVAL_SYSTEM_PROMPT)
                    // 用 Bean 上 @Tool 注解生成 ToolCallback，避免 MCP Provider 不暴露 @Tool 方法导致工具调用失效
                    .defaultToolCallbacks(ToolCallbacks.from(
                            docSearchTool, keywordSearchTool, webSearchTool, memoryTool))
                    .build();

            agentClient.prompt()
                    .user(rewrittenQuery)
                    .call()
                    .content();

            List<RetrievedChunk> chunks = AgentToolContext.get().getChunks();
            Map<String, Object> memory   = AgentToolContext.get().getMemoryContext();
            List<String> calledTools     = AgentToolContext.get().getCalledTools();

            // LLM 未发起任何工具调用（弱模型 / Mock 模型 / 工具调用失败）时，降级为规则检索，避免空上下文
            if (chunks.isEmpty()) {
                log.warn("[MindCrewAgent] LLM 未召回内容（tools={}），降级为规则检索", calledTools);
                updateTrace(state, 3, "LLM 未调用检索工具，降级为规则检索");
                return fallbackMultiRetrieve(state, rewrittenQuery, emitter);
            }

            if (!memory.isEmpty()) {
                state.getMemoryContext().putAll(memory);
            }
            state.setSelectedTools(calledTools);
            state.setIntentType("llm_driven");

            // 记录 MCP 调用到 state（供前端展示）
            for (String tool : calledTools) {
                recordMcpCall(state, tool,
                        Map.of("query", rewrittenQuery),
                        chunks.stream().filter(c -> toolMatchesSource(c, tool)).count() + " chunks",
                        0L);
            }

            sendSseEvent(emitter, "intent", Map.of(
                    "intentType", "llm_driven",
                    "tools", calledTools,
                    "confidence", 1.0
            ));

            log.info("[MindCrewAgent] LLM驱动检索完成: tools={} chunks={}", calledTools, chunks.size());
            return chunks;

        } catch (Exception e) {
            log.warn("[MindCrewAgent] LLM驱动工具调用失败，降级为规则检索: {}", e.getMessage());
            // 降级：用 QueryRouter 规则路由 + 直接 Java 调用
            return fallbackMultiRetrieve(state, rewrittenQuery, emitter);
        } finally {
            AgentToolContext.clear();
        }
    }

    private boolean toolMatchesSource(RetrievedChunk chunk, String toolName) {
        if (chunk.getSource() == null) return false;
        return switch (toolName) {
            case DocSearchTool.TOOL_NAME     -> chunk.getSource() == RetrievedChunk.Source.VECTOR
                                             || chunk.getSource() == RetrievedChunk.Source.HYBRID;
            case KeywordSearchTool.TOOL_NAME -> chunk.getSource() == RetrievedChunk.Source.BM25;
            case WebSearchTool.TOOL_NAME     -> chunk.getSource() == RetrievedChunk.Source.WEB;
            default -> false;
        };
    }

    /**
     * 降级路径：规则意图识别 + 直接 Java 方法调用（原有逻辑）
     */
    private List<RetrievedChunk> fallbackMultiRetrieve(AgentState state,
                                                        String rewrittenQuery,
                                                        SseEmitter emitter) {
        QueryRouter.IntentResult intentResult = queryRouter.route(rewrittenQuery);
        state.setIntentType(intentResult.getIntentType());
        state.setSelectedTools(new ArrayList<>(intentResult.getTools()));

        sendSseEvent(emitter, "intent", Map.of(
                "intentType", intentResult.getIntentType(),
                "tools", intentResult.getTools(),
                "confidence", intentResult.getConfidence()
        ));

        return multiRetrieve(state, rewrittenQuery, emitter);
    }

    // ==================== 多路召回（降级路径）====================

    /**
     * 根据意图选用工具执行多路召回（降级使用，直接 Java 调用）
     */
    private List<RetrievedChunk> multiRetrieve(AgentState state,
                                                String rewrittenQuery,
                                                SseEmitter emitter) {
        List<String> tools = state.getSelectedTools();
        List<RetrievedChunk> vectorResults = new ArrayList<>();
        List<RetrievedChunk> bm25Results   = new ArrayList<>();
        List<RetrievedChunk> webResults    = new ArrayList<>();

        int vectorTopK = safeGetInt("rag.vector_top_k", 20);
        int bm25TopK   = safeGetInt("rag.bm25_top_k", 20);

        if (tools.contains(DocSearchTool.TOOL_NAME)) {
            long t0 = System.currentTimeMillis();
            vectorResults = docSearchTool.searchDocs(rewrittenQuery, vectorTopK, state.getKbIds());
            recordMcpCall(state, DocSearchTool.TOOL_NAME,
                    Map.of("query", rewrittenQuery, "topK", vectorTopK, "kbIds", state.getKbIds()),
                    vectorResults.size() + " chunks",
                    System.currentTimeMillis() - t0);
        }

        if (tools.contains(KeywordSearchTool.TOOL_NAME)) {
            long t0 = System.currentTimeMillis();
            bm25Results = keywordSearchTool.keywordSearch(rewrittenQuery, state.getKbIds(), null);
            recordMcpCall(state, KeywordSearchTool.TOOL_NAME,
                    Map.of("query", rewrittenQuery, "kbIds", state.getKbIds()),
                    bm25Results.size() + " chunks",
                    System.currentTimeMillis() - t0);
        }

        if (tools.contains(WebSearchTool.TOOL_NAME)) {
            long t0 = System.currentTimeMillis();
            webResults = webSearchTool.webSearch(rewrittenQuery, 5);
            recordMcpCall(state, WebSearchTool.TOOL_NAME,
                    Map.of("query", rewrittenQuery, "maxResults", 5),
                    webResults.size() + " results",
                    System.currentTimeMillis() - t0);
        }

        if (tools.contains(MemoryTool.RECALL_TOOL_NAME)) {
            long t0 = System.currentTimeMillis();
            Map<String, Object> memories = memoryTool.recallMemory(state.getUserId(), null);
            state.setMemoryContext(new LinkedHashMap<>(memories));
            recordMcpCall(state, MemoryTool.RECALL_TOOL_NAME,
                    Map.of("userId", state.getUserId(), "topic", null),
                    memories,
                    System.currentTimeMillis() - t0);
        }

        // 合并所有结果
        List<RetrievedChunk> all = new ArrayList<>();
        all.addAll(vectorResults);
        all.addAll(bm25Results);
        all.addAll(webResults);
        return all;
    }

    private List<RetrievedChunk> retrieveSelectedDocumentChunks(AgentState state) {
        List<Long> kbIds = state.getKbIds();
        if (kbIds == null || kbIds.isEmpty()) {
            return List.of();
        }

        List<KbKnowledgeBase> docs = kbKnowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KbKnowledgeBase>()
                        .in(KbKnowledgeBase::getId, kbIds)
                        .eq(KbKnowledgeBase::getDeleted, 0)
                        .eq(KbKnowledgeBase::getStatus, "ready")
                        .orderByAsc(KbKnowledgeBase::getId)
        );
        if (docs.isEmpty()) {
            return List.of();
        }

        int totalBudget = safeGetInt("rag.document_scope_chunk_budget", DEFAULT_DOCUMENT_SCOPE_CHUNK_BUDGET);
        int perDocBudget = Math.max(4, Math.max(1, totalBudget / docs.size()));

        // 检测问题中的位置偏好（如"第一个章节"→偏好文档前部）
        String positionBias = detectQueryPositionBias(state.getQuery());

        List<RetrievedChunk> result = new ArrayList<>();

        for (KbKnowledgeBase doc : docs) {
            List<RetrievedChunk> scopedChunks = loadChunksFromDocument(doc, perDocBudget, positionBias);
            result.addAll(scopedChunks);
        }

        recordMcpCall(state, "selected_document_scope",
                Map.of("kbIds", kbIds, "mode", "direct_read", "budget", totalBudget),
                result.size() + " chunks",
                0L);
        return assignSequentialScores(result);
    }

    private List<RetrievedChunk> loadChunksFromDocument(KbKnowledgeBase doc, int chunkBudget, String positionBias) {
        List<KbChunk> persistedChunks = kbChunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .eq(KbChunk::getKbId, doc.getId())
                        .orderByAsc(KbChunk::getChunkIndex)
        );
        if (!persistedChunks.isEmpty()) {
            return sampleWithPositionBias(persistedChunks, chunkBudget, positionBias).stream()
                    .map(chunk -> toRetrievedChunk(chunk, doc))
                    .collect(Collectors.toList());
        }

        if (doc.getFileUrl() == null || doc.getFileUrl().isBlank()) {
            return List.of();
        }

        Path filePath = Paths.get(uploadPath, doc.getFileUrl());
        if (!Files.exists(filePath)) {
            log.warn("[MindCrewAgent] 选中文档直读失败，文件不存在: kbId={}, path={}", doc.getId(), filePath.toAbsolutePath());
            return List.of();
        }

        try (InputStream inputStream = Files.newInputStream(filePath)) {
            String text = documentExtractor.extract(inputStream, doc.getFileType());
            if (text == null || text.isBlank()) {
                return List.of();
            }
            List<TextChunker.TextChunk> chunks = textChunker.chunk(text, doc.getId(), doc.getCategory());
            return sampleWithPositionBias(chunks, chunkBudget, positionBias).stream()
                    .map(chunk -> toRetrievedChunk(chunk, doc))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[MindCrewAgent] 选中文档直读失败: kbId={}, error={}", doc.getId(), e.getMessage());
            return List.of();
        }
    }

    private RetrievedChunk toRetrievedChunk(KbChunk chunk, KbKnowledgeBase doc) {
        RetrievedChunk retrieved = new RetrievedChunk();
        retrieved.setId("selected_doc_" + doc.getId() + "_" + chunk.getChunkIndex());
        retrieved.setContent(chunk.getContent());
        retrieved.setKnowledgeBaseId(doc.getId());
        retrieved.setSourceName(doc.getName());
        retrieved.setSourceRef(doc.getFileUrl());
        retrieved.setCategory(doc.getCategory());
        retrieved.setSource(RetrievedChunk.Source.HYBRID);
        applyChunkMetadata(retrieved, chunk.getMetadata());
        retrieved.setChunkIndex(chunk.getChunkIndex());
        return retrieved;
    }

    private RetrievedChunk toRetrievedChunk(TextChunker.TextChunk chunk, KbKnowledgeBase doc) {
        RetrievedChunk retrieved = new RetrievedChunk();
        retrieved.setId("selected_doc_" + doc.getId() + "_" + chunk.getChunkIndex());
        retrieved.setContent(chunk.getContent());
        retrieved.setKnowledgeBaseId(doc.getId());
        retrieved.setSourceName(doc.getName());
        retrieved.setSourceRef(doc.getFileUrl());
        retrieved.setCategory(doc.getCategory());
        retrieved.setContentType(chunk.getContentType());
        retrieved.setChapter(chunk.getChapter());
        retrieved.setPageNumber(chunk.getPageNumber());
        retrieved.setChunkIndex(chunk.getChunkIndex());
        retrieved.setSource(RetrievedChunk.Source.HYBRID);
        return retrieved;
    }

    private void applyChunkMetadata(RetrievedChunk retrieved, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return;
        }
        try {
            JSONObject metadata = JSON.parseObject(metadataJson);
            retrieved.setChapter(metadata.getString("chapter"));
            Integer pageNumber = metadata.getInteger("pageNumber");
            if (pageNumber != null) {
                retrieved.setPageNumber(pageNumber);
            }
            retrieved.setContentType(metadata.getString("contentType"));
        } catch (Exception e) {
            log.debug("[MindCrewAgent] 解析 chunk metadata 失败: {}", e.getMessage());
        }
    }

    /**
     * 检测用户问题是否隐含对文档前部或后部的位置偏好。
     * 纯正则匹配，无需 LLM 调用。
     *
     * @return "front"（偏好文档前部）、"back"（偏好文档尾部）或 null（无偏好）
     */
    private String detectQueryPositionBias(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }

        // 前向偏好：第一章、开头、概述、目录等
        java.util.regex.Pattern frontPattern = java.util.regex.Pattern.compile(
                "第一[章节篇个条部分课]|第1[章节篇个条部分课]|开头|开篇|开始|前面|最前面|起初|最初|"
                + "前言|概述|概览|目录|引入|介绍|首[个章节]|起始|最开始");
        if (frontPattern.matcher(question).find()) {
            return "front";
        }

        // 后向偏好：最后一章、结尾、结论、总结等
        java.util.regex.Pattern backPattern = java.util.regex.Pattern.compile(
                "最后一[章节篇个条部分课]|末尾|结尾|结论|总结|小结|最后|尾部|末页|结语|后记");
        if (backPattern.matcher(question).find()) {
            return "back";
        }

        // 章节编号："第X章"、"第X节"
        java.util.regex.Pattern chapterPattern = java.util.regex.Pattern.compile(
                "第([一二三四五六七八九十百千0-9]+)[章节]");
        if (chapterPattern.matcher(question).find()) {
            return "front"; // 章节按顺序排列，近似为前向偏好
        }

        return null;
    }

    private <T> List<T> sampleEvenly(List<T> items, int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (items.size() <= limit) {
            return new ArrayList<>(items);
        }

        Set<Integer> indices = new LinkedHashSet<>();
        if (limit <= 1) {
            indices.add(items.size() / 2);
        } else {
            for (int i = 0; i < limit; i++) {
                int idx = (int) Math.round((double) i * (items.size() - 1) / (limit - 1));
                indices.add(idx);
            }
        }

        List<T> sampled = new ArrayList<>(indices.size());
        for (Integer idx : indices) {
            sampled.add(items.get(idx));
        }
        return sampled;
    }

    /**
     * 位置感知采样。
     * - "front": 前 {@code rag.front_bias_ratio} 比例的切片强制取自列表前部，其余均匀采样
     * - "back":  末尾部分强制取自列表尾部
     * - null:    回退到 {@link #sampleEvenly(List, int)}
     *
     * 列表必须已按文档原始顺序（chunkIndex ASC）排序。
     */
    private <T> List<T> sampleWithPositionBias(List<T> items, int limit, String positionBias) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        if (items.size() <= limit) {
            return new ArrayList<>(items);
        }
        if (positionBias == null) {
            return sampleEvenly(items, limit);
        }

        float frontRatio = safeGetFloat("rag.front_bias_ratio", 0.35f);
        int biasCount = Math.max(2, Math.min(limit, Math.round(limit * frontRatio)));

        if ("front".equals(positionBias)) {
            int remainingBudget = limit - biasCount;
            List<T> result = new ArrayList<>();

            // 强制取前 biasCount 条
            int take = Math.min(biasCount, items.size());
            for (int i = 0; i < take; i++) {
                result.add(items.get(i));
            }

            // 剩余预算从尾部均匀采样，保证全文覆盖
            if (remainingBudget > 0 && items.size() > take) {
                result.addAll(sampleEvenly(items.subList(take, items.size()), remainingBudget));
            }
            return result;
        }

        if ("back".equals(positionBias)) {
            int backStart = Math.max(0, items.size() - biasCount);
            int remainingBudget = limit - biasCount;

            List<T> result = remainingBudget > 0 && backStart > 0
                    ? new ArrayList<>(sampleEvenly(items.subList(0, backStart), remainingBudget))
                    : new ArrayList<>();

            for (int i = backStart; i < items.size(); i++) {
                result.add(items.get(i));
            }
            return result;
        }

        // 未知偏置类型：回退均匀采样
        return sampleEvenly(items, limit);
    }

    private List<RetrievedChunk> assignSequentialScores(List<RetrievedChunk> chunks) {
        for (int i = 0; i < chunks.size(); i++) {
            float score = Math.max(0.1f, 1.0f - i * 0.03f);
            chunks.get(i).setScore(score);
            chunks.get(i).setRerankScore(score);
            chunks.get(i).setRrfRank(i + 1);
        }
        return chunks;
    }

    // ==================== Self-Reflection ====================

    /**
     * 执行自纠错审查（最多 MAX_REFLECTION_ROUNDS 轮）
     * 若审查不通过则重新检索并重新生成（同步降级：取已有答案）
     */
    private String runSelfReflection(AgentState state,
                                      String question,
                                      List<RetrievedChunk> chunks,
                                      String answer,
                                      SseEmitter emitter) {
        String currentAnswer = answer;
        int round = 0;

        while (round < SelfReflection.MAX_REFLECTION_ROUNDS) {
            round++;
            state.setReflectionRound(round);

            SelfReflection.ReflectionResult result =
                    selfReflection.reflect(question, chunks, currentAnswer);

            // 记录到日志
            Map<String, Object> log = new LinkedHashMap<>();
            log.put("round", round);
            log.put("passed", result.isPassed());
            log.put("confidence", result.getConfidence());
            log.put("reason", result.getReason());
            log.put("issues", result.getIssues());
            state.getReflectionLog().add(log);

            sendSseEvent(emitter, "reflection", Map.of(
                    "round", round,
                    "passed", result.isPassed(),
                    "confidence", result.getConfidence()
            ));

            if (result.isPassed()) {
                state.setReflectionPassed(true);
                return currentAnswer;
            }

            // 不通过：记录日志，保持原答案（流式模式下无法重新生成，返回原答案+说明）
            if (round < SelfReflection.MAX_REFLECTION_ROUNDS) {
                log.put("action", "答案质量不足，保持原回答");
            }
        }

        // 达到最大轮次：标记不通过
        state.setReflectionPassed(false);
        return currentAnswer;
    }

    // ==================== 缓存回放 ====================

    private void replayFromCache(QaConversation conversation,
                                  RagCachedResult cached,
                                  long startTime,
                                  SseEmitter emitter,
                                  AgentState state) {
        log.info("[MindCrewAgent] 缓存命中，回放 conversationId={}", conversation.getId());
        try {
            sendSseEvent(emitter, "rewrite", Map.of(
                    "original", cached.getRetrievalLog().getOrDefault("originalQuery", ""),
                    "rewritten", cached.getRewrittenQuery() != null ? cached.getRewrittenQuery() : "",
                    "fromCache", true
            ));
            sendSseEvent(emitter, "start", Map.of("message", "开始生成（缓存）..."));

            String answer = cached.getAnswer();
            for (int i = 0; i < answer.length(); i += CACHE_CHUNK_SIZE) {
                String chunk = answer.substring(i, Math.min(i + CACHE_CHUNK_SIZE, answer.length()));
                sendSseEvent(emitter, "token", Map.of("content", chunk));
            }

            int elapsed = (int) (System.currentTimeMillis() - startTime);
            // 缓存命中也持久化检索日志（虽然没真跑 RAG，但前端展示一致）
            String cachedRetrievalLog = cached.getRetrievalLog() == null
                    ? null : JSON.toJSONString(cached.getRetrievalLog());
            saveQaMessage(conversation.getId(), "assistant", answer,
                    JSON.toJSONString(cached.getSources()), null, null, null,
                    cachedRetrievalLog, null, null, null, java.math.BigDecimal.ZERO, elapsed);

            updateConversation(conversation);

            Map<String, Object> donePayload = new LinkedHashMap<>();
            donePayload.put("sources", cached.getSources());
            donePayload.put("isFallback", cached.isFallback());
            donePayload.put("isEmergency", cached.isEmergency());
            donePayload.put("responseTime", elapsed);
            donePayload.put("conversationId", conversation.getId());
            donePayload.put("fromCache", true);
            donePayload.put("retrievalLog", cached.getRetrievalLog());
            sendSseEvent(emitter, "done", donePayload);

            emitter.complete();
        } catch (Exception e) {
            log.error("[MindCrewAgent] 缓存回放失败", e);
            emitter.completeWithError(e);
        }
    }

    // ==================== 数据库操作 ====================

    private QaConversation getOrCreateConversation(String userId, Long conversationId, String question, List<Long> kbIds) {
        Long userIdLong = parseUserId(userId);

        if (conversationId != null) {
            QaConversation existing = qaConversationMapper.selectById(conversationId);
            if (existing != null && userIdLong.equals(existing.getUserId())) {
                return existing;
            }
        }

        QaConversation conv = new QaConversation();
        conv.setUserId(userIdLong);
        conv.setTitle(question.length() > 20 ? question.substring(0, 20) + "..." : question);
        conv.setKbIds(KbIdsParser.toJson(kbIds));
        conv.setMessageCount(0);
        conv.setLastActive(LocalDateTime.now());
        qaConversationMapper.insert(conv);
        return conv;
    }

    private void saveQaMessage(Long conversationId, String role, String content,
                                String sources, String agentTrace,
                                String mcpCalls, String reflectionLog) {
        saveQaMessage(conversationId, role, content, sources, agentTrace,
                mcpCalls, reflectionLog, null);
    }

    /**
     * 持久化 QaMessage（含 RAG 检索日志）
     * 用于 assistant 消息保留 retrievalLog，刷新/切换会话后仍可查检索过程
     */
    private void saveQaMessage(Long conversationId, String role, String content,
                                String sources, String agentTrace,
                                String mcpCalls, String reflectionLog,
                                String retrievalLog) {
        saveQaMessage(conversationId, role, content, sources, agentTrace, mcpCalls, reflectionLog,
                retrievalLog, null, null, null, null, null);
    }

    /**
     * 持久化 QaMessage · 带模型/Token/成本/时延埋点（任务 13/14 检索分析与成本核算用）
     */
    private void saveQaMessage(Long conversationId, String role, String content,
                                String sources, String agentTrace,
                                String mcpCalls, String reflectionLog,
                                String retrievalLog,
                                String modelName, Integer inputTokens, Integer outputTokens,
                                java.math.BigDecimal costCny, Integer latencyMs) {
        try {
            QaMessage msg = new QaMessage();
            msg.setConversationId(conversationId);
            msg.setRole(role);
            msg.setContent(content);
            msg.setSources(sources);
            msg.setAgentTrace(agentTrace);
            msg.setMcpCalls(mcpCalls);
            msg.setReflectionLog(reflectionLog);
            msg.setRetrievalLog(retrievalLog);
            msg.setFeedback(0);
            if (modelName != null)    msg.setModelName(modelName);
            if (inputTokens != null)  msg.setInputTokens(inputTokens);
            if (outputTokens != null) msg.setOutputTokens(outputTokens);
            if (costCny != null)      msg.setCostCny(costCny);
            if (latencyMs != null) {
                msg.setLatencyMs(latencyMs);
                msg.setResponseTime(latencyMs);
            }
            qaMessageMapper.insert(msg);
        } catch (Exception e) {
            log.warn("[MindCrewAgent] 保存 QaMessage 失败: {}", e.getMessage());
        }
    }

    private void updateConversation(QaConversation conv) {
        conv.setMessageCount(conv.getMessageCount() + 2);
        conv.setLastActive(LocalDateTime.now());
        qaConversationMapper.updateById(conv);
    }

    private String buildConversationHistory(Long conversationId) {
        List<QaMessage> messages = qaMessageMapper.selectList(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getConversationId, conversationId)
                        .orderByDesc(QaMessage::getCreateTime)
                        .last("LIMIT 6")
        );
        if (messages.isEmpty()) return "";
        Collections.reverse(messages);
        return messages.stream()
                .map(m -> ("user".equals(m.getRole()) ? "用户" : "助手") + "：" + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    // ==================== 工具方法 ====================

    /** 批量补全文档名 + 溯源元数据（时间戳/媒体类型/远程对象名）*/
    private void enrichSourceNames(List<RetrievedChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) return;
        Set<Long> kbIds = chunks.stream()
                .map(RetrievedChunk::getKnowledgeBaseId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (kbIds.isEmpty()) return;

        // kb 名称 + 文件类型
        List<KbKnowledgeBase> kbList = kbKnowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KbKnowledgeBase>()
                        .in(KbKnowledgeBase::getId, kbIds)
                        .select(KbKnowledgeBase::getId, KbKnowledgeBase::getName, KbKnowledgeBase::getFileType)
        );
        Map<Long, KbKnowledgeBase> kbMap = kbList.stream()
                .collect(Collectors.toMap(KbKnowledgeBase::getId, kb -> kb));

        // chunk metadata 批量查
        List<KbChunk> dbChunks = kbChunkMapper.selectList(
                new LambdaQueryWrapper<KbChunk>()
                        .in(KbChunk::getKbId, kbIds)
                        .select(KbChunk::getKbId, KbChunk::getContent, KbChunk::getMetadata)
        );
        Map<String, String> metaMap = new HashMap<>();
        for (KbChunk db : dbChunks) {
            String content = db.getContent();
            if (content == null) continue;
            String key = db.getKbId() + "::" + (content.length() <= 100 ? content : content.substring(0, 100));
            if (db.getMetadata() != null) metaMap.put(key, db.getMetadata());
        }

        chunks.forEach(chunk -> {
            KbKnowledgeBase kb = kbMap.get(chunk.getKnowledgeBaseId());
            if (kb != null) {
                chunk.setSourceName(kb.getName() != null ? kb.getName() : "文档");
                if (chunk.getMediaType() == null) {
                    chunk.setMediaType(inferMediaTypeByFileType(kb.getFileType()));
                }
            }
            // 时间戳/对象名
            String content = chunk.getContent();
            if (content != null && chunk.getStartMs() == null) {
                String key = chunk.getKnowledgeBaseId() + "::" + (content.length() <= 100 ? content : content.substring(0, 100));
                String metaJson = metaMap.get(key);
                if (metaJson != null) {
                    try {
                        com.alibaba.fastjson2.JSONObject m = com.alibaba.fastjson2.JSON.parseObject(metaJson);
                        if (chunk.getStartMs() == null) chunk.setStartMs(m.getLong("startMs"));
                        if (chunk.getEndMs() == null) chunk.setEndMs(m.getLong("endMs"));
                        if (chunk.getSpeakerId() == null) chunk.setSpeakerId(m.getString("speakerId"));
                        if (chunk.getSourceObjectName() == null) chunk.setSourceObjectName(m.getString("sourceObjectName"));
                    } catch (Exception ignored) {}
                }
            }
        });
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

    private Map<String, Object> buildRetrievalLog(AgentState state,
                                                   String originalQuery,
                                                   String rewrittenQuery,
                                                   int vectorCount,
                                                   int bm25Count,
                                                   int webCount,
                                                   int rrfCount,
                                                   int rerankCount) {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("originalQuery", originalQuery);
        log.put("rewrittenQuery", rewrittenQuery);
        log.put("intentType", state.getIntentType());
        log.put("selectedTools", state.getSelectedTools());
        log.put("retrievalMode", state.isDocumentScopedRetrieval() ? "selected_document" : "search");
        log.put("selectedKbIds", new ArrayList<>(state.getKbIds()));
        log.put("vectorResults", vectorCount);
        log.put("bm25Results", bm25Count);
        log.put("webResults", webCount);
        log.put("rrfCount", rrfCount);
        log.put("rerankTop", rerankCount);
        log.put("memoryKeys", new ArrayList<>(state.getMemoryContext().keySet()));
        return log;
    }

    private List<RetrievedChunk> mergeForRerank(List<RetrievedChunk> fused, List<RetrievedChunk> webResults) {
        Map<String, RetrievedChunk> merged = new LinkedHashMap<>();
        for (RetrievedChunk chunk : fused) {
            merged.put(buildChunkKey(chunk), chunk);
        }
        for (RetrievedChunk chunk : webResults) {
            merged.putIfAbsent(buildChunkKey(chunk), chunk);
        }
        return new ArrayList<>(merged.values());
    }

    private String buildChunkKey(RetrievedChunk chunk) {
        String content = chunk.getContent() != null ? chunk.getContent() : "";
        String sourceRef = chunk.getSourceRef() != null ? chunk.getSourceRef() : "";
        return chunk.getSource() + "|" + sourceRef + "|" + content.substring(0, Math.min(60, content.length()));
    }

    /** 向 agentTrace 追加一条思考记录 */
    private void addTrace(AgentState state, int step,
                           String thought, String action,
                           String actionInput, String observation) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("step", step);
        entry.put("thought", thought);
        entry.put("action", action);
        entry.put("actionInput", actionInput);
        entry.put("observation", observation);
        state.getAgentTrace().add(entry);
    }

    /** 更新最后一条 trace 的 observation */
    private void updateTrace(AgentState state, int step, String observation) {
        state.getAgentTrace().stream()
                .filter(e -> Integer.valueOf(step).equals(e.get("step")))
                .findFirst()
                .ifPresent(e -> e.put("observation", observation));
    }

    /** 记录 MCP Tool 调用 */
    private void recordMcpCall(AgentState state, String tool,
                                Object input, Object output, long latencyMs) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("tool", tool);
        call.put("input", input);
        call.put("output", output);
        call.put("latencyMs", latencyMs);
        call.put("timestamp", System.currentTimeMillis());
        state.getMcpCalls().add(call);
    }

    /** 安全获取 int 配置，找不到时返回默认值 */
    private int safeGetInt(String key, int defaultValue) {
        try {
            return aiConfigHolder.getInt(key);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** 安全获取 float 配置，找不到时返回默认值 */
    private float safeGetFloat(String key, float defaultValue) {
        try {
            return aiConfigHolder.getFloat(key);
        } catch (Exception e) {
            return defaultValue;
        }
    }

    /** 解析用户 ID（字符串 → Long） */
    private Long parseUserId(String userId) {
        if (userId == null) return 0L;
        try {
            return Long.parseLong(userId);
        } catch (NumberFormatException e) {
            return (long) userId.hashCode();
        }
    }

    private void sendSseEvent(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(JSON.toJSONString(data)));
        } catch (Exception e) {
            log.warn("[MindCrewAgent] SSE发送失败 event={}: {}", event, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // Golden Pair 短路 · 任务 6
    // ─────────────────────────────────────────────

    /**
     * 在主流程入口判断 query 是否命中已校正的 golden pair。
     * 命中时：
     *   - SSE 发 golden-hit 事件（前端可显示"基于人工校正"角标）
     *   - 流式回放 standard_answer（按 token 切片 + 微延迟，体验跟正常生成一致）
     *   - 保存 assistant 消息，标记 sources 为 golden_pair 类型
     *   - 发 done 事件，关闭 emitter
     * @return true 表示已命中并完成 SSE，外层应直接 return；false 表示未命中，走正常 RAG
     */
    private boolean tryGoldenPairShortCircuit(String question,
                                              QaConversation conversation,
                                              SseEmitter emitter,
                                              long startTime) {
        com.simon.MindCrew.service.QaGoldenPairService.HitOutcome hit;
        try {
            hit = goldenPairService.searchHit(question);
        } catch (Exception e) {
            log.warn("[MindCrewAgent] Golden Pair 搜索异常，回退正常流程: {}", e.getMessage());
            return false;
        }
        if (hit == null) return false;

        com.simon.MindCrew.entity.QaGoldenPair pair = hit.pair();
        log.info("[MindCrewAgent] ✓ Golden Pair 命中 · pairId={} score={}", pair.getId(), hit.score());

        // 1) 发送 golden-hit 信号给前端
        sendSseEvent(emitter, "golden-hit", Map.of(
                "pairId", pair.getId(),
                "score", hit.score(),
                "matchedQuestion", pair.getQuestion(),
                "verifiedBy", "人工校正"
        ));

        // 2) 流式回放标准答案
        sendSseEvent(emitter, "start", Map.of("message", "命中已审核标准答案", "fromGoldenPair", true));
        String answer = pair.getStandardAnswer();
        try {
            replayAsTokenStream(emitter, answer);
        } catch (Exception e) {
            log.warn("[MindCrewAgent] Golden Pair 流式回放失败: {}", e.getMessage());
        }

        // 3) 构造 sources（如果 golden pair 自带）
        String sourcesJson = pair.getSourcesJson();
        if (sourcesJson == null || sourcesJson.isBlank()) {
            sourcesJson = JSON.toJSONString(java.util.List.of(Map.of(
                    "type", "golden_pair",
                    "pairId", pair.getId(),
                    "matchedQuestion", pair.getQuestion(),
                    "verifiedBy", "人工校正",
                    "hitCount", pair.getHitCount() == null ? 1 : pair.getHitCount() + 1
            )));
        }

        // 4) 保存 assistant 消息（Golden Pair 命中 · 无 LLM 成本，仅记时延）
        saveQaMessage(conversation.getId(), "assistant", answer, sourcesJson, null, null, null,
                null, null, null, null, java.math.BigDecimal.ZERO,
                (int) (System.currentTimeMillis() - startTime));
        updateConversation(conversation);

        // 5) done
        long elapsed = System.currentTimeMillis() - startTime;
        sendSseEvent(emitter, "done", Map.of(
                "conversationId", conversation.getId(),
                "elapsedMs", elapsed,
                "fromGoldenPair", true,
                "pairId", pair.getId(),
                "score", hit.score(),
                "sources", JSON.parseArray(sourcesJson)
        ));
        emitter.complete();
        return true;
    }

    // ─────────────────────────────────────────────
    // 图片输入分析 · 任务 10
    // ─────────────────────────────────────────────

    /** 图片分析返回结果 · 增强后的 query + 用户消息 sources（含图片 URL）*/
    private record ImageAnalysisResult(String augmentedQuery, String sourcesJson) {}

    /**
     * 对用户上传的每张图片走 VL 识别（OCR + 描述），
     * 把内容拼到 query 里让下游 RAG 能基于图片内容检索知识库，
     * 同时把图片 URL 作为用户消息的 sources，让前端能展示原图。
     *
     * 不做 mock / 兜底：
     *   - 任何一张图 VL 调用失败 → 抛异常让整个对话失败（前端可见明确错误）
     *   - 不静默忽略
     */
    private ImageAnalysisResult analyzeImages(List<String> imageObjectNames, String userQuestion, SseEmitter emitter) {
        sendSseEvent(emitter, "image-analysis", Map.of(
                "status", "start",
                "imageCount", imageObjectNames.size()
        ));

        StringBuilder visionContext = new StringBuilder();
        java.util.List<Map<String, Object>> sourceList = new java.util.ArrayList<>();
        long t0 = System.currentTimeMillis();

        for (int i = 0; i < imageObjectNames.size(); i++) {
            String objectName = imageObjectNames.get(i);
            try (java.io.InputStream in = fileStorage.getFileStream(objectName)) {
                byte[] bytes = in.readAllBytes();
                String mimeType = guessMimeType(objectName);

                com.simon.MindCrew.service.knowledge.VisionRecognizer.VisionResult vr =
                        visionRecognizer.recognize(bytes, mimeType);
                if (!vr.success()) {
                    throw new RuntimeException("VL 识别第 " + (i + 1) + " 张图失败: "
                            + (vr.description() == null ? "(无错误信息)" : vr.description()));
                }

                String ocr  = vr.ocrText() == null ? "" : vr.ocrText().trim();
                String desc = vr.description() == null ? "" : vr.description().trim();

                visionContext.append("\n【图片 ").append(i + 1).append(" · 内容描述】\n").append(desc);
                if (!ocr.isBlank() && !"无文字".equals(ocr)) {
                    visionContext.append("\n【图片 ").append(i + 1).append(" · 文字提取】\n").append(ocr);
                }

                Map<String, Object> src = new java.util.LinkedHashMap<>();
                src.put("type", "user_image");
                src.put("objectName", objectName);
                src.put("url", fileStorage.getFileUrl(objectName));
                src.put("description", desc);
                src.put("ocrText", ocr);
                sourceList.add(src);

                log.info("[Agent] 图片 {} VL 完成 · ocrLen={} descLen={}",
                        i + 1, ocr.length(), desc.length());
            } catch (Exception e) {
                log.error("[Agent] 图片识别失败 · objectName={}", objectName, e);
                sendSseEvent(emitter, "image-analysis", Map.of(
                        "status", "error",
                        "imageIndex", i + 1,
                        "message", e.getMessage()
                ));
                throw new RuntimeException("图片 " + (i + 1) + " 识别失败: " + e.getMessage(), e);
            }
        }

        // 拼接增强后的 query
        String augmented = visionContext +
                "\n\n【用户问题】\n" +
                (userQuestion == null || userQuestion.isBlank() ? "请基于以上图片回答" : userQuestion);

        sendSseEvent(emitter, "image-analysis", Map.of(
                "status", "done",
                "imageCount", imageObjectNames.size(),
                "elapsedMs", System.currentTimeMillis() - t0
        ));

        return new ImageAnalysisResult(augmented, JSON.toJSONString(sourceList));
    }

    /** 任务 13 · 取当前活跃模型名（来自 yml 或 LlmProvider DB） */
    private String getActiveModelName() {
        try {
            String m = aiConfigHolder.getActiveChatModelName();
            if (m != null && !m.isBlank()) return m;
            m = aiConfigHolder.getString("llm.model");
            return m == null || m.isBlank() ? "qwen-plus" : m;
        } catch (Exception e) {
            return "qwen-plus";
        }
    }

    /** 粗略 token 估算 · 中文 1.5 字符/token，英文按 4 字符/token */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() * 2 / 3);
    }

    /** 任务 7 · userId 转 Long 后查 ACL 可访问 KB */
    private List<Long> resolveAccessibleKbIds(String userIdStr) {
        if (userIdStr == null || userIdStr.isBlank()) return List.of();
        try {
            return kbAclService.listAccessibleKbIds(Long.parseLong(userIdStr));
        } catch (NumberFormatException e) {
            log.warn("[MindCrewAgent] userId 不是数字，跳过 ACL: {}", userIdStr);
            return List.of();
        }
    }

    private static String guessMimeType(String objectName) {
        if (objectName == null) return "image/jpeg";
        String lower = objectName.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp"))  return "image/bmp";
        return "image/jpeg";
    }

    /** 把答案按字符切片发 token 事件，模拟流式输出（30ms/字符，给前端打字机效果） */
    private void replayAsTokenStream(SseEmitter emitter, String fullAnswer) throws InterruptedException {
        if (fullAnswer == null || fullAnswer.isEmpty()) return;
        // 按字符发；超长时合并发送（避免太慢）
        int chunkSize = fullAnswer.length() > 500 ? 4 : 1;
        for (int i = 0; i < fullAnswer.length(); i += chunkSize) {
            int end = Math.min(i + chunkSize, fullAnswer.length());
            String token = fullAnswer.substring(i, end);
            sendSseEvent(emitter, "token", Map.of("content", token));
            Thread.sleep(15);    // 体感流畅，整段 < 7.5s
        }
    }
}
