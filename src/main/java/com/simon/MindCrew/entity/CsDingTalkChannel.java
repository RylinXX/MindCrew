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
 * 客服中心 · 钉钉机器人渠道
 */
@Data
@TableName("cs_dingtalk_channel")
public class CsDingTalkChannel {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;

    private String appKey;

    /** 钉钉 AppSecret · AES 加密存储 */
    private String appSecret;

    /** 回调验签密钥 · AES 加密存储 */
    private String signSecret;

    private String robotCode;

    private String webhookUrl;

    private Integer enabled;

    /** JSON 数组字符串 · 渠道 AI 检索范围 */
    private String kbIds;

    private String welcomeText;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
