package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教练模式 · 单道题 · 任务 9
 */
@Data
@TableName("coach_question")
public class CoachQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;
    private Integer seq;

    private String question;
    /** short_answer · multiple_choice · true_false */
    private String questionType;

    /** JSON 数组（选择题选项） */
    private String options;
    private String expectedAnswer;
    private String explanation;

    private Long sourceChunkId;
    private Long sourceKbId;
    private String sourceKbName;

    /** 出题时引用的原文片段 · 反幻觉证据 · 必须是 source chunk 的真子串 */
    private String sourceQuote;

    private String difficulty;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
