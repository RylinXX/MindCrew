package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 配置中心实体
 * 持久化所有可动态调整的 RAG / LLM / 缓存 / 安全参数
 */
@Data
@TableName("sys_ai_config")
public class SysAiConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键（全局唯一），如 rag.vector_top_k */
    private String configKey;

    /** 当前配置值（统一字符串存储） */
    private String configValue;

    /** 值类型：string / integer / float */
    private String valueType;

    /** 分组：rag / llm / cache / safety */
    private String groupName;

    /** 前端展示名称 */
    private String label;

    /** 配置说明 */
    private String description;

    /** 出厂默认值（用于一键重置） */
    private String defaultValue;

    /** 最小值约束（前端校验用） */
    private String minValue;

    /** 最大值约束 */
    private String maxValue;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
