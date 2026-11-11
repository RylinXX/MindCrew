package com.simon.MindCrew.config;

import com.simon.MindCrew.mcp.DocSearchTool;
import com.simon.MindCrew.mcp.KeywordSearchTool;
import com.simon.MindCrew.mcp.MemoryTool;
import com.simon.MindCrew.mcp.WebSearchTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具注册配置
 *
 * <p>将现有基于 {@code @Tool} 注解的方法显式注册为 {@link ToolCallbackProvider}，
 * 供 Spring AI MCP Server Starter 自动转换为 MCP Tool 规范并对外暴露。
 */
@Slf4j
@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider mcpToolCallbackProvider(
            DocSearchTool docSearchTool,
            KeywordSearchTool keywordSearchTool,
            WebSearchTool webSearchTool,
            MemoryTool memoryTool) {
        log.info("注册 MCP 工具: {}, {}, {}, {}, {}",
                DocSearchTool.TOOL_NAME,
                KeywordSearchTool.TOOL_NAME,
                WebSearchTool.TOOL_NAME,
                MemoryTool.RECALL_TOOL_NAME,
                MemoryTool.STORE_TOOL_NAME);

        return MethodToolCallbackProvider.builder()
                .toolObjects(docSearchTool, keywordSearchTool, webSearchTool, memoryTool)
                .build();
    }
}
