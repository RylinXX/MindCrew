package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 教练模式 · 练习会话 · 任务 9
 */
@Data
@TableName("coach_session")
public class CoachSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** JSON 数组（["1","3"]）· 留空表示全量可访问 KB */
    private String kbIds;

    /** 范围摘要（前端展示用） */
    private String kbScopeLabel;

    /** easy · medium · hard */
    private String difficulty;

    private Integer questionTotal;
    private Integer questionDone;
    private Integer correctCount;
    private Integer totalScore;

    /** active · finished · abandoned */
    private String status;

    private LocalDateTime startAt;
    private LocalDateTime endAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
