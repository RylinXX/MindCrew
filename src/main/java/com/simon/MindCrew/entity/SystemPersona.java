package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Soul 人格定义实体。
 *
 * 业务语义：
 *   一个 persona 定义"AI 用什么风格、什么底线、什么口吻回答用户"，
 *   作为 system prompt 注入到面向最终用户的 LLM 调用（Writer / 单 Agent 回答阶段）。
 *
 * 不影响：
 *   - Planner / Researcher / Critic 等内部 Agent（它们有专属 prompt）
 *   - 工具调用 / 检索 / 评测 等内部流程
 */
@Data
@TableName("system_persona")
public class SystemPersona {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 人格名称（如"严谨研究员"），全局唯一 */
    private String name;

    /** 简短描述（管理后台列表显示） */
    private String description;

    /** 完整的 system prompt 内容 */
    private String systemPrompt;

    /** 温度（0.0-2.0） */
    private BigDecimal temperature;

    /** 推荐使用的模型（可空表示用全局默认） */
    private String modelName;

    /** 是否在 prompt 末尾追加反讨好规则段（1=是） */
    private Integer antiSycophancy;

    /** 是否为系统默认人格（全局唯一） */
    private Integer isDefault;

    /** 是否启用 */
    private Integer enabled;

    /** 排序权重 */
    private Integer sortOrder;

    /** 创建者用户 ID（系统预置为 null） */
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
