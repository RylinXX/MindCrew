package com.simon.MindCrew.crew.enums;

/**
 * Multi-Agent 任务状态机。
 *
 * 正常流转：PENDING → PLANNING → RESEARCHING → WRITING → REVIEWING → COMPLETED
 * 重写分支：REVIEWING → REVISING → REVIEWING（最多一轮）
 * 失败分支：任意状态 → FAILED
 */
public enum TaskStatus {

    PENDING,        // 已创建，等待启动
    PLANNING,       // Planner 工作中
    RESEARCHING,    // 一个或多个 Researcher 并行工作
    WRITING,        // Writer 撰写报告
    REVIEWING,      // Critic 审查
    REVISING,       // 评审不合格，Writer 重写
    COMPLETED,      // 成功完成
    FAILED          // 任意环节失败
}
