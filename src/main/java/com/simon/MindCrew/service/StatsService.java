package com.simon.MindCrew.service;

import java.util.Map;

/**
 * 数据统计服务接口
 */
public interface StatsService {

    /**
     * 首页统计数据（大屏）
     * @param timeRange  时间范围：today / week / month
     */
    Map<String, Object> getDashboard(String timeRange);
}
