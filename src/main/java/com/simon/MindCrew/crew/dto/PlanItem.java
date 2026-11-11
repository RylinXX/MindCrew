package com.simon.MindCrew.crew.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Planner 输出的单个子任务。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PlanItem {

    /** 子任务索引（1 起） */
    private Integer index;

    /** 子任务标题（如"竞品价格对比"） */
    private String title;

    /** Researcher 要回答的具体问题 */
    private String query;

    /** 该子任务在最终报告中对应的章节名 */
    private String section;
}
