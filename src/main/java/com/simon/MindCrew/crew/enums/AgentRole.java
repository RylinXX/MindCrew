package com.simon.MindCrew.crew.enums;

/**
 * Multi-Agent Crew 中的角色定义。
 * 每个 Agent 有独立的 system prompt、工具子集和职责边界。
 */
public enum AgentRole {

    /** 任务分解者：把用户问题拆成 3-5 个可独立调研的子主题 */
    PLANNER("PLANNER",   "任务规划师", "把复杂问题分解成可独立调研的子主题"),

    /** 调研员：对单个子主题执行深度检索（复用 MindCrewAgent） */
    RESEARCHER("RESEARCHER", "调研员",    "针对子主题执行多路检索并汇总要点"),

    /** 撰写员：把所有 Researcher 的发现合成为结构化报告 */
    WRITER("WRITER",     "撰写员",    "把各路调研发现合成为带引用的结构化报告"),

    /** 评审员：审查报告质量，决定是否需要重写 */
    CRITIC("CRITIC",     "评审员",    "对报告进行事实性、完整性、引用充分性评分");

    private final String code;
    private final String label;
    private final String responsibility;

    AgentRole(String code, String label, String responsibility) {
        this.code = code;
        this.label = label;
        this.responsibility = responsibility;
    }

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public String getResponsibility() { return responsibility; }
}
