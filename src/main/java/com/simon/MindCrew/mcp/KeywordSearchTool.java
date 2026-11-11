package com.simon.MindCrew.mcp;

import com.simon.MindCrew.agent.AgentToolContext;
import com.simon.MindCrew.service.rag.BM25Retriever;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * MCP Tool：关键词文档检索
 * 封装 BM25Retriever，支持元数据字段过滤
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordSearchTool {

    private final BM25Retriever bm25Retriever;

    /** 工具注册名 */
    public static final String TOOL_NAME = "keyword_search";

    /**
     * 关键词 BM25 检索文档切片（MCP Tool：可被外部 AI 客户端调用）
     *
     * @param query   检索关键词或短句
     * @param kbIds   知识库 ID 列表过滤（传 null/空不过滤）
     * @param filters 额外过滤条件（目前支持：category/contentType）
     * @return 检索到的切片列表
     */
    @Tool(description = "关键词BM25文档检索：根据关键词精确匹配从知识库中检索文档切片，适用于精确条款查询、编号检索等场景")
    public List<RetrievedChunk> keywordSearch(
            @ToolParam(description = "检索关键词或短句") String query,
            @ToolParam(description = "知识库ID列表过滤，不传则检索全部", required = false) List<Long> kbIds,
            @ToolParam(description = "额外过滤条件，支持 category/contentType/topK 字段", required = false) Map<String, Object> filters) {
        try {
            // Agent 上下文激活时，用上下文中的 kbIds 作为兜底
            List<Long> effectiveKbIds = (kbIds != null && !kbIds.isEmpty()) ? kbIds
                    : (AgentToolContext.isActive() ? AgentToolContext.get().getKbIds() : null);

            // 从 filters 中提取 category 过滤（对应 Milvus category 字段）
            String categoryFilter = null;
            if (filters != null && filters.containsKey("category")) {
                categoryFilter = filters.get("category").toString();
            }

            int topK = 20;
            if (filters != null && filters.containsKey("topK")) {
                try {
                    topK = Integer.parseInt(filters.get("topK").toString());
                } catch (NumberFormatException ignored) {
                    // 使用默认值
                }
            }

            List<RetrievedChunk> results = bm25Retriever.retrieve(query, categoryFilter, effectiveKbIds, topK);

            // contentType 过滤
            if (filters != null && filters.containsKey("contentType")) {
                String ct = filters.get("contentType").toString();
                results = results.stream()
                        .filter(c -> ct.equals(c.getContentType()))
                        .toList();
            }

            if (AgentToolContext.isActive()) {
                AgentToolContext.get().addChunks(TOOL_NAME, results);
            }

            log.info("[KeywordSearchTool] query='{}' kbIds={} results={}", query, effectiveKbIds, results.size());
            return results;

        } catch (Exception e) {
            log.error("[KeywordSearchTool] 检索异常: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
