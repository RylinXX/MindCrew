package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.McpToolRegistry;
import com.simon.MindCrew.mapper.McpToolRegistryMapper;
import com.simon.MindCrew.mcp.DocSearchTool;
import com.simon.MindCrew.mcp.KeywordSearchTool;
import com.simon.MindCrew.mcp.MemoryTool;
import com.simon.MindCrew.mcp.WebSearchTool;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MCP 工具管理控制台 API
 */
@Slf4j
@RestController
@RequestMapping("/api/mcp")
@RequiredArgsConstructor
public class McpConsoleController {

    private final McpToolRegistryMapper mcpToolRegistryMapper;
    private final DocSearchTool docSearchTool;
    private final KeywordSearchTool keywordSearchTool;
    private final WebSearchTool webSearchTool;
    private final MemoryTool memoryTool;

    // ==================== 工具列表 ====================

    /**
     * 获取所有工具列表
     * GET /api/mcp/tools
     */
    @GetMapping("/tools")
    public Result<List<McpToolRegistry>> listTools() {
        List<McpToolRegistry> tools = mcpToolRegistryMapper.selectList(
                new LambdaQueryWrapper<McpToolRegistry>()
                        .orderByAsc(McpToolRegistry::getName));
        return Result.success(tools);
    }

    /**
     * 获取单个工具详情
     * GET /api/mcp/tools/{id}
     */
    @GetMapping("/tools/{id}")
    public Result<McpToolRegistry> getToolById(@PathVariable Long id) {
        McpToolRegistry tool = mcpToolRegistryMapper.selectById(id);
        if (tool == null) {
            return Result.error("工具不存在");
        }
        return Result.success(tool);
    }

    /**
     * 更新工具状态
     * PUT /api/mcp/tools/{id}/status
     * body: {status: "active" | "disabled"}
     */
    @PutMapping("/tools/{id}/status")
    public Result<Void> updateToolStatus(
            @PathVariable Long id,
            @RequestBody ToolStatusDTO dto) {

        McpToolRegistry tool = mcpToolRegistryMapper.selectById(id);
        if (tool == null) {
            return Result.error("工具不存在");
        }
        if (!"active".equals(dto.getStatus()) && !"disabled".equals(dto.getStatus())) {
            return Result.error("无效的状态值，仅允许 active 或 disabled");
        }
        tool.setStatus(dto.getStatus());
        mcpToolRegistryMapper.updateById(tool);
        log.info("[McpConsoleController] 工具状态更新: id={}, status={}", id, dto.getStatus());
        return Result.success();
    }

    // ==================== 统计 ====================

    /**
     * 工具调用统计汇总
     * GET /api/mcp/stats
     */
    @GetMapping("/stats")
    public Result<Map<String, Object>> getStats() {
        List<McpToolRegistry> tools = mcpToolRegistryMapper.selectList(null);

        long totalCallCount = tools.stream()
                .mapToLong(t -> t.getCallCount() != null ? t.getCallCount() : 0L)
                .sum();

        double avgLatency = tools.stream()
                .filter(t -> t.getAvgLatencyMs() != null && t.getAvgLatencyMs() > 0)
                .mapToInt(McpToolRegistry::getAvgLatencyMs)
                .average()
                .orElse(0.0);

        long activeCount = tools.stream()
                .filter(t -> "active".equals(t.getStatus()))
                .count();

        long disabledCount = tools.stream()
                .filter(t -> "disabled".equals(t.getStatus()))
                .count();

        // Top 调用工具
        Optional<McpToolRegistry> topTool = tools.stream()
                .max(Comparator.comparingLong(t -> t.getCallCount() != null ? t.getCallCount() : 0L));

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalTools", tools.size());
        stats.put("activeTools", activeCount);
        stats.put("disabledTools", disabledCount);
        stats.put("totalCallCount", totalCallCount);
        stats.put("avgLatencyMs", Math.round(avgLatency));
        stats.put("topTool", topTool.map(McpToolRegistry::getName).orElse(null));
        stats.put("toolDetails", tools.stream().map(t -> {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("id", t.getId());
            detail.put("name", t.getName());
            detail.put("callCount", t.getCallCount() != null ? t.getCallCount() : 0);
            detail.put("avgLatencyMs", t.getAvgLatencyMs() != null ? t.getAvgLatencyMs() : 0);
            detail.put("status", t.getStatus());
            return detail;
        }).collect(Collectors.toList()));

        return Result.success(stats);
    }

    /**
     * 测试工具连通性（真实调用工具）
     * POST /api/mcp/tools/{id}/test
     */
    @PostMapping("/tools/{id}/test")
    public Result<Map<String, Object>> testTool(@PathVariable Long id) {
        McpToolRegistry tool = mcpToolRegistryMapper.selectById(id);
        if (tool == null) {
            return Result.error("工具不存在");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("toolId", tool.getId());
        result.put("toolName", tool.getName());
        result.put("mode", tool.getMode());
        result.put("status", tool.getStatus());

        if ("disabled".equals(tool.getStatus())) {
            result.put("testResult", "SKIPPED");
            result.put("message", "工具已禁用，跳过连通性测试");
            result.put("latencyMs", 0);
            return Result.success(result);
        }

        long start = System.currentTimeMillis();
        try {
            Object pingResult = invokePing(tool.getName());
            long latency = System.currentTimeMillis() - start;
            result.put("testResult", "SUCCESS");
            result.put("message", "工具调用正常");
            result.put("latencyMs", latency);
            result.put("timestamp", System.currentTimeMillis());
            result.put("sampleOutput", pingResult);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("[McpConsoleController] 工具连通性测试失败: tool={} error={}", tool.getName(), e.getMessage());
            result.put("testResult", "ERROR");
            result.put("message", e.getMessage());
            result.put("latencyMs", latency);
            result.put("timestamp", System.currentTimeMillis());
        }

        return Result.success(result);
    }

    // ==================== 私有方法 ====================

    /**
     * 真实调用工具执行最小 ping，返回简要输出用于验证连通性
     */
    private Object invokePing(String toolName) {
        return switch (toolName) {
            case DocSearchTool.TOOL_NAME ->
                    docSearchTool.searchDocs("连通性测试", 1, null);
            case KeywordSearchTool.TOOL_NAME ->
                    keywordSearchTool.keywordSearch("测试", null, null);
            case WebSearchTool.TOOL_NAME ->
                    webSearchTool.webSearch("测试", 1);
            case MemoryTool.RECALL_TOOL_NAME ->
                    memoryTool.recallMemory("__ping__", null);
            case MemoryTool.STORE_TOOL_NAME ->
                    memoryTool.storeMemory("__ping__", Map.of("_test", "ping"));
            default -> throw new IllegalArgumentException("未知工具: " + toolName);
        };
    }

    // ==================== DTO ====================

    @Data
    public static class ToolStatusDTO {
        private String status;
    }
}
