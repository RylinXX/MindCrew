package com.simon.MindCrew.crew.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Critic Agent 的审查结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReviewResult {

    /** 综合评分 0~1 */
    private Double score;

    /** 是否通过 */
    private Boolean passed;

    /** 事实性评分 */
    private Double factuality;

    /** 完整性评分 */
    private Double completeness;

    /** 引用充分性评分 */
    private Double citationCoverage;

    /** 发现的问题列表 */
    private List<String> issues;

    /** 改进建议（用于 Writer 重写时参考） */
    private String suggestion;
}
