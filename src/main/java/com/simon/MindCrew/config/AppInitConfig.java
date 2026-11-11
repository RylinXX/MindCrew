package com.simon.MindCrew.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.McpToolRegistry;
import com.simon.MindCrew.mapper.McpToolRegistryMapper;
import com.simon.MindCrew.mcp.DocSearchTool;
import com.simon.MindCrew.mcp.KeywordSearchTool;
import com.simon.MindCrew.mcp.MemoryTool;
import com.simon.MindCrew.mcp.WebSearchTool;
import com.simon.MindCrew.service.knowledge.FileStorageService;
import com.simon.MindCrew.service.knowledge.MilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 应用启动初始化
 * 自动创建 MinIO Bucket 和 Milvus Collection
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppInitConfig implements ApplicationRunner {

    private final FileStorageService fileStorageService;
    private final MilvusService milvusService;
    private final McpToolRegistryMapper mcpToolRegistryMapper;

    /** 系统内置工具定义：name / description / mode */
    private static final List<Object[]> BUILTIN_TOOLS = List.of(
            new Object[]{DocSearchTool.TOOL_NAME,       "语义向量文档检索：根据语义相似度从知识库中检索最相关的文档切片",  "embedded"},
            new Object[]{KeywordSearchTool.TOOL_NAME,   "关键词BM25文档检索：根据关键词精确匹配从知识库中检索文档切片",   "embedded"},
            new Object[]{WebSearchTool.TOOL_NAME,       "互联网实时检索：调用阿里云OpenSearch获取最新网页信息",           "remote"},
            new Object[]{MemoryTool.RECALL_TOOL_NAME,   "召回用户长期记忆：从Redis读取用户偏好等跨会话记忆",              "embedded"},
            new Object[]{MemoryTool.STORE_TOOL_NAME,    "写入用户长期记忆：将用户明确表达的偏好持久化到Redis",             "embedded"}
    );

    @Override
    public void run(ApplicationArguments args) {
        log.info("========== MindCrew 系统初始化 ==========");
        log.info("文件存储后端: {}", fileStorageService.type());
        // 存储 / 向量库属于外部中间件：初始化失败不阻断应用启动（降级运行，相关能力不可用）
        try {
            fileStorageService.initBucket();
        } catch (Exception e) {
            log.warn("[Init] 对象存储初始化失败（上传/存储能力不可用，其余功能正常）: {}", e.getMessage());
        }
        try {
            milvusService.initCollection();
        } catch (Exception e) {
            log.warn("[Init] Milvus 初始化失败（向量检索降级为空，关键词检索仍可用）: {}", e.getMessage());
        }
        syncMcpToolRegistry();
        log.info("========== 初始化完成 ==========");
    }

    /**
     * 启动时将内置工具同步到 mcp_tool_registry 表。
     * 已存在的记录不覆盖（保留调用统计），缺失的补充插入。
     */
    private void syncMcpToolRegistry() {
        for (Object[] def : BUILTIN_TOOLS) {
            String name = (String) def[0];
            boolean exists = mcpToolRegistryMapper.selectCount(
                    new LambdaQueryWrapper<McpToolRegistry>()
                            .eq(McpToolRegistry::getName, name)) > 0;
            if (!exists) {
                McpToolRegistry registry = new McpToolRegistry();
                registry.setName(name);
                registry.setDescription((String) def[1]);
                registry.setMode((String) def[2]);
                registry.setCallCount(0L);
                registry.setAvgLatencyMs(0);
                registry.setStatus("active");
                mcpToolRegistryMapper.insert(registry);
                log.info("[McpToolRegistry] 注册内置工具: {}", name);
            }
        }
    }
}
