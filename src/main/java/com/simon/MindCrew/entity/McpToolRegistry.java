package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * MCP 工具注册实体
 * 对应数据表: mcp_tool_registry
 */
@Data
@TableName("mcp_tool_registry")
public class McpToolRegistry {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 工具名称（唯一） */
    private String name;

    /** 工具描述 */
    private String description;

    /** 模式: embedded（内嵌）/ remote（远程） */
    private String mode;

    /** 累计调用次数 */
    private Long callCount;

    /** 平均延迟（毫秒） */
    private Integer avgLatencyMs;

    /** 状态: active/disabled */
    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
