package com.simon.MindCrew.agent;

import com.simon.MindCrew.service.rag.RetrievedChunk;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 执行状态载体
 * 在 ReAct 推理循环中传递，记录每一步的思考、行动与观察
 */
@Data
public class AgentState {

    /** 原始用户问题 */
    private String query;

    /** 经 QueryRewriter 改写后的问题 */
    private String rewrittenQuery;

    /**
     * 意图类型
     * knowledge_query / exact_search / realtime / compound / followup
     */
    private String intentType;

    /** 本次推理选中的工具列表 */
    private List<String> selectedTools = new ArrayList<>();

    /** 多路召回 + 重排序后的切片集合 */
    private List<RetrievedChunk> retrievedChunks = new ArrayList<>();

    /** LLM 最终生成的回答 */
    private String finalAnswer;

    /**
     * ReAct 推理链日志
     * 每条记录结构：{step, thought, action, actionInput, observation}
     */
    private List<Map<String, Object>> agentTrace = new ArrayList<>();

    /**
     * MCP Tool 调用记录
     * 每条记录结构：{tool, input, output, latencyMs, timestamp}
     */
    private List<Map<String, Object>> mcpCalls = new ArrayList<>();

    /**
     * 自纠错审查日志
     * 每条记录结构：{round, passed, confidence, reason, issues}
     */
    private List<Map<String, Object>> reflectionLog = new ArrayList<>();

    /** 最终自纠错是否通过 */
    private boolean reflectionPassed;

    /** 当前纠错轮次（最多 MAX_REFLECTION_ROUNDS） */
    private int reflectionRound;

    /** 当前用户 ID（字符串，兼容 Long 和 UUID） */
    private String userId;

    /** 关联会话 ID */
    private Long conversationId;

    /** 当前请求关联的知识库 ID 列表 */
    private List<Long> kbIds = new ArrayList<>();

    /** 运行时内存上下文 */
    private Map<String, Object> memoryContext = new java.util.LinkedHashMap<>();

    /** 是否命中文档级直读模式（选中文档后直接读取文档内容，而非语义召回） */
    private boolean documentScopedRetrieval;

    /** 用户是否显式选择了知识库范围（区别于 ACL 默认可见范围） */
    private boolean explicitKbScope;
}
