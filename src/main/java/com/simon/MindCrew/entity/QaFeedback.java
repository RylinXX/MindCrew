package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户对 AI 答复的反馈 · 任务 6 校正反哺闭环
 * 对应表 qa_feedback
 */
@Data
@TableName("qa_feedback")
public class QaFeedback {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 关联 qa_message.id（AI 回答那条消息） */
    private Long messageId;

    private Long conversationId;
    private Long userId;

    /** up · 赞，down · 踩 */
    private String rating;

    private String comment;

    /** 用户/审核员提供的标准答案 */
    private String correctionText;

    /** 来源引用 JSON */
    private String correctionSources;

    /** pending · 待审核 / approved · 已收录 / rejected · 已驳回 */
    private String status;

    private Long reviewerId;
    private String reviewerNote;
    private LocalDateTime reviewedAt;

    /** 审核通过后生成的 golden pair id */
    private Long goldenPairId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
