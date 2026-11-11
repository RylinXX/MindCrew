package com.simon.MindCrew.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 客服中心 · 客服消息
 */
@Data
@TableName("cs_message")
public class CsMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** user / ai / agent / system */
    private String role;

    private String content;

    /** JSON 数组字符串 · AI 答案来源 */
    private String sources;

    /** JSON · RAG 检索过程日志 */
    private String retrievalLog;

    /** 人工坐席 sys_user.id（role=agent 时） */
    private Long senderId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
