package com.simon.MindCrew.mcp;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.simon.MindCrew.agent.AgentToolContext;
import com.simon.MindCrew.entity.McpToolRegistry;
import com.simon.MindCrew.mapper.McpToolRegistryMapper;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import com.simon.MindCrew.service.rag.VectorRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * MCP Tool：语义文档检索
 * 封装 VectorRetriever，对外提供标准化 Tool 接口
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocSearchTool {

    private final VectorRetriever vectorRetriever;
    private final McpToolRegistryMapper mcpToolRegistryMapper;

    /** 工具注册名（与 mcp_tool_registry 表中 name 字段对应） */
    public static final String TOOL_NAME = "doc_search";

    /**
     * 语义检索文档切片（MCP Tool：可被外部 AI 客户端调用）
     *
     * @param query 检索查询词
     * @param topK  返回数量
     * @param kbIds 知识库 ID 过滤（传 null/空表示全库检索）
     * @return 检索到的切片列表
     */
    @Tool(description = "语义向量文档检索：根据语义相似度从知识库中检索最相关的文档切片，适用于通用知识查询、概念解释等场景")
    public List<RetrievedChunk> searchDocs(
            @ToolParam(description = "检索查询词，支持自然语言描述") String query,
            @ToolParam(description = "返回文档切片数量，建议 5-20") int topK,
            @ToolParam(description = "知识库ID列表过滤，不传则检索全部知识库", required = false) List<Long> kbIds) {
        long start = System.currentTimeMillis();
        try {
            // Agent 上下文激活时，用上下文中的 kbIds 作为兜底（LLM 无需传递此参数）
            List<Long> effectiveKbIds = (kbIds != null && !kbIds.isEmpty()) ? kbIds
                    : (AgentToolContext.isActive() ? AgentToolContext.get().getKbIds() : null);

            List<RetrievedChunk> results = vectorRetriever.retrieve(query, null, effectiveKbIds, topK);

            if (AgentToolContext.isActive()) {
                AgentToolContext.get().addChunks(TOOL_NAME, results);
            }

            long latency = System.currentTimeMillis() - start;
            updateCallStats(latency);

            log.info("[DocSearchTool] query='{}' kbIds={} topK={} results={} latency={}ms",
                    query, effectiveKbIds, topK, results.size(), latency);
            return results;

        } catch (Exception e) {
            log.error("[DocSearchTool] 检索异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 更新工具调用统计（callCount 自增，avgLatencyMs 滑动平均）
     */
    private void updateCallStats(long latencyMs) {
        try {
            // 先查出现有记录
            McpToolRegistry registry = mcpToolRegistryMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<McpToolRegistry>()
                            .eq(McpToolRegistry::getName, TOOL_NAME)
                            .last("LIMIT 1")
            );

            if (registry == null) {
                // 自动注册
                registry = new McpToolRegistry();
                registry.setName(TOOL_NAME);
                registry.setDescription("语义向量文档检索工具");
                registry.setMode("embedded");
                registry.setCallCount(1L);
                registry.setAvgLatencyMs((int) latencyMs);
                registry.setStatus("active");
                mcpToolRegistryMapper.insert(registry);
            } else {
                long newCount = registry.getCallCount() == null ? 1L : registry.getCallCount() + 1;
                int existAvg = registry.getAvgLatencyMs() == null ? 0 : registry.getAvgLatencyMs();
                // 滑动平均：newAvg = (oldAvg * (n-1) + latency) / n
                int newAvg = (int) ((existAvg * (newCount - 1) + latencyMs) / newCount);

                mcpToolRegistryMapper.update(null,
                        new LambdaUpdateWrapper<McpToolRegistry>()
                                .eq(McpToolRegistry::getName, TOOL_NAME)
                                .set(McpToolRegistry::getCallCount, newCount)
                                .set(McpToolRegistry::getAvgLatencyMs, newAvg)
                );
            }
        } catch (Exception e) {
            log.warn("[DocSearchTool] 更新调用统计失败: {}", e.getMessage());
        }
    }
}
