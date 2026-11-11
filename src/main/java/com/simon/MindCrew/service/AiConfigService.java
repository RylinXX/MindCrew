package com.simon.MindCrew.service;

import com.simon.MindCrew.entity.SysAiConfig;

import java.util.List;
import java.util.Map;

/**
 * AI 配置中心 Service
 */
public interface AiConfigService {

    /** 查询全部配置，按 groupName 分组返回 */
    Map<String, List<SysAiConfig>> getAllGrouped();

    /** 批量更新配置值（写 DB + 刷新内存） */
    void batchUpdate(Map<String, String> params);

    /** 将指定分组的 configValue 重置为 defaultValue */
    void resetGroup(String groupName);

    /** 将全部配置重置为默认值 */
    void resetAll();
}
