package com.simon.MindCrew.crew.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Multi-Agent 步骤记录。
 *
 * 一个 Task 包含多条 Step：
 *  - 1 条 PLANNER 步骤
 *  - N 条 RESEARCHER 步骤（按 Plan 子任务数）
 *  - 1 条 WRITER 步骤（或 2 条，含重写）
 *  - 1 条 CRITIC 步骤（或 2 条，含重审）
 */
@Data
@TableName("agent_step")
public class AgentStep {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    /** 步骤序号（任务内自增，从 1 起） */
    private Integer stepIndex;

    /** 对应 AgentRole.code */
    private String agentRole;

    /** 步骤名称（用于前端展示） */
    private String stepName;

    /** Researcher 的具体子任务问题 */
    private String subtask;

    /** 输入摘要 */
    private String input;

    /** 输出（JSON 或文本） */
    private String output;

    /** 状态：RUNNING/DONE/FAILED/SKIPPED */
    private String status;

    /** 耗时(ms) */
    private Long elapsedMs;

    /** Token 估算 */
    private Integer tokens;

    /** 失败原因 */
    private String errorMsg;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
