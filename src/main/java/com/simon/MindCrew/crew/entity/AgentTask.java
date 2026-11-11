package com.simon.MindCrew.crew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Multi-Agent 任务主表实体。
 */
@Data
@TableName("agent_task")
public class AgentTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 发起用户 */
    private Long userId;

    /** 关联会话（可空） */
    private Long conversationId;

    /** Fork 的原任务 ID（NULL 表示原始任务） */
    private Long parentTaskId;

    /** Fork 起点的步骤序号 */
    private Integer forkedFromStep;

    /** 用户 Fork 时的编辑说明 */
    private String forkEditSummary;

    /** 原始问题 */
    private String query;

    /** 检索知识库 ID 列表 JSON */
    private String kbIds;

    /** 状态机当前值 — 字符串与 TaskStatus 一一对应 */
    private String status;

    /** 当前活跃 Agent 角色（PLANNER/RESEARCHER/WRITER/CRITIC） */
    private String currentRole;

    /** Planner 输出的子任务列表 JSON */
    private String planJson;

    /** 最终报告 Markdown */
    private String finalReport;

    /** Critic 评分 0~1 */
    private BigDecimal reviewScore;

    /** 已重写轮次 */
    private Integer revisionCount;

    /** 总步骤数（含 Researcher 各子任务） */
    private Integer totalSteps;

    /** 总 Token 估算 */
    private Integer totalTokens;

    /** 总耗时 */
    private Long elapsedMs;

    /** 失败原因 */
    private String errorMsg;

    private LocalDateTime startTime;
    private LocalDateTime endTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
