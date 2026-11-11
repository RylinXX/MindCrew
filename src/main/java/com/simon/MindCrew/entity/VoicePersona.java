package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 音色配置 · 任务 14
 *
 * 预置 CosyVoice v2 多个常用音色；后续可扩展自定义复刻（owner_user_id 非 null）
 */
@Data
@TableName("voice_persona")
public class VoicePersona {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String voiceId;
    /** cosyvoice / volcengine / minimax / custom */
    private String provider;
    private String model;

    /** male / female / child / neutral */
    private String gender;
    private String language;

    private String description;
    private String tags;

    private Integer sampleRate;

    /** 自定义音色归属用户（预置音色为 null） */
    private Long ownerUserId;

    private Integer isDefault;
    private Integer enabled;
    private Integer sortOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
