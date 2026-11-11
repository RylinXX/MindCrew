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
 * 客服中心 · 客服会话
 */
@Data
@TableName("cs_session")
public class CsSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 会话编号（对外暴露，访客 token 以此签发） */
    private String sessionNo;

    /** 访客标识 */
    private String visitorKey;

    private String visitorName;

    /** SSO appId（来源外部系统） */
    private String sourceAppId;

    /** web / dingtalk / api / embed */
    private String channel;

    /** JSON 数组字符串 · 本会话 AI 检索范围 */
    private String kbIds;

    /** qa_conversation.id · AI 多轮上下文（首次回答后回写） */
    private Long conversationId;

    /** bot / human / closed */
    private String status;

    /** 人工坐席 sys_user.id */
    private Long assigneeId;

    private Integer messageCount;

    private String lastMessage;

    private LocalDateTime lastActive;

    /** 本地 sys_user.id（SSO 登录态） */
    private Long ssoUserId;

    /** 满意度 1-5 */
    private Integer rating;

    private String ratingNote;

    private String ip;

    private String userAgent;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;

    /** 非数据库字段 · 工作台列表展示坐席名 */
    @TableField(exist = false)
    private String assigneeName;
}
