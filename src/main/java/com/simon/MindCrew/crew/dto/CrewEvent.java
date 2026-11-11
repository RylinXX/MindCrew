package com.simon.MindCrew.crew.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

/**
 * SSE 推给前端的事件 DTO。
 * 前端通过 EventSource 监听，按 type 渲染不同 UI。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CrewEvent {

    /**
     * 事件类型：
     *  task.start, task.done, task.failed
     *  agent.start, agent.done, agent.failed
     *  planner.plan       — 规划完成，data: PlanItem[]
     *  researcher.finding — 单个 Researcher 完成，data: Finding
     *  writer.token       — 报告流式 token，data: { delta }
     *  writer.done        — 报告完成，data: { report }
     *  critic.review      — 评审完成，data: ReviewResult
     *  revision.start     — 触发重写
     */
    private String type;

    /** 当前 Agent 角色（可为 null） */
    private String role;

    /** 步骤序号（可为 null） */
    private Integer stepIndex;

    /** 任务总进度 0~1 */
    private Double progress;

    /** 事件负载 */
    private Map<String, Object> data;

    public static CrewEvent of(String type) {
        return new CrewEvent(type, null, null, null, new HashMap<>());
    }

    public CrewEvent role(String r)             { this.role = r; return this; }
    public CrewEvent step(Integer i)            { this.stepIndex = i; return this; }
    public CrewEvent progress(Double p)         { this.progress = p; return this; }
    public CrewEvent put(String k, Object v)    {
        if (this.data == null) this.data = new HashMap<>();
        this.data.put(k, v); return this;
    }
}
