package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 对外 API 调用日志 · 任务 11
 * 11.6 增加 kb_id 字段使可按 KB 维度统计与查询
 */
@Data
@TableName("api_call_log")
public class ApiCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long keyId;
    /** 该次调用作用于哪个 KB（11.6 维度过滤用） */
    private Long kbId;

    private String api;
    private String question;
    private Integer statusCode;
    private Integer inputTokens;
    private Integer outputTokens;
    private BigDecimal costCny;
    private Integer latencyMs;
    private String ip;
    private String userAgent;
    private String errorMsg;
    private LocalDateTime calledAt;
}
