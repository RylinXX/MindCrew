package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话会话实体
 * 对应数据表: qa_conversation
 */
@Data
@TableName("qa_conversation")
public class QaConversation {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联用户ID */
    private Long userId;

    /** 会话标题（首次提问自动生成） */
    private String title;

    /** 关联的知识库ID列表 JSON，如 [1, 2, 3] */
    private String kbIds;

    /** 消息条数 */
    private Integer messageCount;

    /** 最后活跃时间 */
    private LocalDateTime lastActive;

    /** 任务 13.5 · 是否被主管/管理员标记为敏感对话 */
    private Integer isFlagged;
    /** 标记备注（涉密 / 越权 / 失实 / 投诉 等） */
    private String flagNote;
    /** 标记人 user_id */
    private Long flaggedBy;
    /** 标记时间 */
    private LocalDateTime flaggedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
