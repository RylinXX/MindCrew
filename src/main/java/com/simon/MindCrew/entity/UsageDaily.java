package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户每日用量聚合 · 任务 13
 * 唯一键 (user_id, stat_date) 保证一天一行
 */
@Data
@TableName("usage_daily")
public class UsageDaily {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private LocalDate statDate;

    private Integer chatCount;
    private Long inputTokens;
    private Long outputTokens;
    private Long embeddingTokens;
    private Integer visionCalls;
    private Integer asrSeconds;
    private BigDecimal costCny;
    private Integer goldenHitCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
