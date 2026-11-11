package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客服中心 · SSO 对接的外部系统
 */
@Data
@TableName("cs_sso_app")
public class CsSsoApp {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String appName;

    /** 外部系统标识（唯一） */
    private String appId;

    /** 签名密钥 · AES 加密存储 */
    private String appSecret;

    private Integer enabled;

    /** 首次登录是否自动建本地账号 */
    private Integer autoCreateUser;

    /** 自动建号默认角色 */
    private String defaultRole;

    private String remark;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
