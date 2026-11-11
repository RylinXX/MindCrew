package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * PII 脱敏全局配置 · 任务 12.2
 * 单行记录（id=1）· 管理员热改
 */
@Data
@TableName("pii_config")
public class PiiConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer enabled;
    private Integer maskPhone;
    private Integer maskIdCard;
    private Integer maskBankCard;
    private Integer maskEmail;
    private Integer maskAddress;
    private Integer applyOnUpload;
    private Integer applyOnResponse;
    private Integer applyOnAudit;
    private Long updatedBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
