package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教练模式 · 用户作答 · 任务 9
 */
@Data
@TableName("coach_answer")
public class CoachAnswer {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long questionId;
    private Long sessionId;
    private Long userId;

    private String userAnswer;
    /** 0 - 100 */
    private Integer score;
    /** correct · partial · wrong */
    private String judgment;
    private String feedback;

    /** JSON 数组 · 推荐复习的 chunk ids */
    private String recommendChunkIds;

    private LocalDateTime answerAt;
}
