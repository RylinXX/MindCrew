package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息实体
 */
@Data
@TableName("qa_message")
public class MedMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    /** 角色: user/assistant */
    private String role;

    private String content;

    /** 引用来源 JSON */
    private String sources;

    /** 检索过程日志 JSON（已废弃，原med_message字段，新表无此列） */
    @TableField(exist = false)
    private String retrievalLog;

    /** 反馈: 1有用 -1无用 0未评 */
    private Integer feedback;

    /** 是否触发兜底（已废弃，原med_message字段，新表无此列） */
    @TableField(exist = false)
    private Integer isFallback;

    private Integer tokensUsed;

    private Integer responseTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
