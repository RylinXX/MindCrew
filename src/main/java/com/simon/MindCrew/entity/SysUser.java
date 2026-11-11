package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 系统用户实体
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    @JsonIgnore
    private String password;

    private String nickname;

    private String avatar;

    /** 系统角色: admin / auditor / user */
    private String role;

    /** 部门 ID（关联 sys_department） · 任务 7 职位独立 KB */
    private Long departmentId;

    /** 职位 ID（关联 sys_position） · 任务 7 · 决定可访问哪些 KB */
    private Long positionId;

    /** 用户偏好 JSON 字段（领域、语言风格等） */
    private String preference;

    private Integer status;

    private LocalDateTime lastLogin;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
