package com.simon.MindCrew.agent;

import com.simon.MindCrew.service.rag.RetrievedChunk;

import java.util.*;

/**
 * Agent 工具调用上下文（ThreadLocal）
 *
 * <p>在 LLM 驱动的工具调用阶段（ChatClient function-calling loop）激活，
 * 各工具 Bean 执行时将结果写入此上下文，Agent 从中统一提取 chunks 和记忆。
 *
 * <p>生命周期：
 * <pre>
 *   AgentToolContext.activate(kbIds, userId);
 *   try {
 *       chatClient.prompt().user(q).call().content(); // 触发工具调用
 *       List&lt;RetrievedChunk&gt; chunks = AgentToolContext.get().getChunks();
 *   } finally {
 *       AgentToolContext.clear();
 *   }
 * </pre>
 */
public class AgentToolContext {

    private static final ThreadLocal<AgentToolContext> CURRENT = new ThreadLocal<>();

    /** 供工具 Bean 使用的知识库过滤范围（LLM 不需要传递该参数） */
    private final List<Long> kbIds;

    /** 供 recall_memory 使用的用户 ID */
    private final String userId;

    /** 各工具执行后写入的检索结果 */
    private final List<RetrievedChunk> chunks = Collections.synchronizedList(new ArrayList<>());

    /** recall_memory 写入的用户记忆 */
    private final Map<String, Object> memoryContext = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 实际被调用的工具名称（用于 SSE intent 事件） */
    private final List<String> calledTools = Collections.synchronizedList(new ArrayList<>());

    private AgentToolContext(List<Long> kbIds, String userId) {
        this.kbIds = kbIds != null ? List.copyOf(kbIds) : List.of();
        this.userId = userId != null ? userId : "";
    }

    // ==================== 静态工厂 ====================

    public static void activate(List<Long> kbIds, String userId) {
        CURRENT.set(new AgentToolContext(kbIds, userId));
    }

    public static void clear() {
        CURRENT.remove();
    }

    public static AgentToolContext get() {
        return CURRENT.get();
    }

    public static boolean isActive() {
        return CURRENT.get() != null;
    }

    // ==================== 读取 ====================

    public List<Long> getKbIds() {
        return kbIds;
    }

    public String getUserId() {
        return userId;
    }

    public List<RetrievedChunk> getChunks() {
        return new ArrayList<>(chunks);
    }

    public Map<String, Object> getMemoryContext() {
        return new LinkedHashMap<>(memoryContext);
    }

    public List<String> getCalledTools() {
        return new ArrayList<>(calledTools);
    }

    // ==================== 写入（由工具 Bean 调用） ====================

    public void addChunks(String toolName, List<RetrievedChunk> results) {
        if (results != null && !results.isEmpty()) {
            chunks.addAll(results);
            if (!calledTools.contains(toolName)) {
                calledTools.add(toolName);
            }
        }
    }

    public void putMemory(String toolName, Map<String, Object> mem) {
        if (mem != null && !mem.isEmpty()) {
            memoryContext.putAll(mem);
            if (!calledTools.contains(toolName)) {
                calledTools.add(toolName);
            }
        }
    }
}
