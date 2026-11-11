package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息实体（核心）
 * 对应数据表: qa_message
 */
@Data
@TableName("qa_message")
public class QaMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联会话ID */
    private Long conversationId;

    /** 角色: user/assistant */
    private String role;

    /** 消息内容（支持Markdown） */
    private String content;

    /** 文档来源 JSON（文档名、页码、片段） */
    private String sources;

    /** ReAct 推理链 JSON（思考→行动→观察） */
    private String agentTrace;

    /** Tool 调用记录 JSON */
    private String mcpCalls;

    /** 自纠错审查日志 JSON */
    private String reflectionLog;

    /** RAG 检索过程日志 JSON（query 改写 / 召回数 / RRF / 重排 / 命中状态） */
    private String retrievalLog;

    /** 反馈: 1有用 -1无用 0未评 */
    private Integer feedback;

    /** 任务 13 · 本条消息用的模型（如 qwen-plus） */
    private String modelName;

    /** 任务 13 · input tokens */
    private Integer inputTokens;

    /** 任务 13 · output tokens */
    private Integer outputTokens;

    /** 任务 13 · 本条成本（人民币元） */
    private java.math.BigDecimal costCny;

    /** 任务 13 · 单消息生成耗时（ms） */
    private Integer latencyMs;

    /** Token 消耗（旧字段，保持兼容） */
    private Integer tokensUsed;

    /** 响应时间（毫秒，旧字段） */
    private Integer responseTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
