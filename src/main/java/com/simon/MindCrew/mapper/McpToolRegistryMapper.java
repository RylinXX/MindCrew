package com.simon.MindCrew.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.simon.MindCrew.entity.McpToolRegistry;
import org.apache.ibatis.annotations.Mapper;

/**
 * MCP 工具注册 Mapper
 * 对应数据表: mcp_tool_registry
 */
@Mapper
public interface McpToolRegistryMapper extends BaseMapper<McpToolRegistry> {
}
