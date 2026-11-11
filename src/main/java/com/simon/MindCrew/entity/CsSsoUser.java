package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客服中心 · SSO 外部账号 → 本地账号映射
 */
@Data
@TableName("cs_sso_user")
public class CsSsoUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 外部系统标识 */
    private String appId;

    private String externalUserId;

    private String externalName;

    /** 本地 sys_user.id */
    private Long userId;

    private LocalDateTime lastLogin;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
