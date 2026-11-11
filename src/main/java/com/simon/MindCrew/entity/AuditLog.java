package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 审计日志 · 任务 12.1
 * 合规追溯所有关键操作
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private String username;
    private String action;
    private String actionLabel;
    private String targetType;
    private String targetId;
    private String targetName;
    private String status;
    private String detailJson;
    private String errorMsg;
    private String ip;
    private String userAgent;
    private Integer latencyMs;
    private LocalDateTime createdAt;
}
