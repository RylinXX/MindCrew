package com.simon.MindCrew.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.SysAiConfig;
import com.simon.MindCrew.mapper.SysAiConfigMapper;
import com.simon.MindCrew.service.AiConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 配置中心 Service 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigServiceImpl implements AiConfigService {

    private final SysAiConfigMapper aiConfigMapper;
    private final AiConfigHolder aiConfigHolder;

    @Override
    public Map<String, List<SysAiConfig>> getAllGrouped() {
        List<SysAiConfig> all = aiConfigMapper.selectList(
                new LambdaQueryWrapper<SysAiConfig>().eq(SysAiConfig::getDeleted, 0));
        return all.stream().collect(Collectors.groupingBy(SysAiConfig::getGroupName));
    }

    @Override
    @Transactional
    public void batchUpdate(Map<String, String> params) {
        boolean llmChanged = false;

        for (Map.Entry<String, String> entry : params.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            SysAiConfig record = aiConfigMapper.selectOne(
                    new LambdaQueryWrapper<SysAiConfig>()
                            .eq(SysAiConfig::getConfigKey, key)
                            .eq(SysAiConfig::getDeleted, 0));
            if (record == null) {
                log.warn("[AiConfig] 未知配置键，跳过: {}", key);
                continue;
            }

            record.setConfigValue(value);
            aiConfigMapper.updateById(record);

            if (key.startsWith("llm.")) llmChanged = true;
        }

        // 刷新内存快照
        aiConfigHolder.updateBatch(params);

        // LLM 参数变更时重建模型实例
        if (llmChanged) {
            aiConfigHolder.refreshLlmModel();
        }

        log.info("[AiConfig] 批量更新 {} 项配置，llmChanged={}", params.size(), llmChanged);
    }

    @Override
    @Transactional
    public void resetGroup(String groupName) {
        List<SysAiConfig> group = aiConfigMapper.selectList(
                new LambdaQueryWrapper<SysAiConfig>()
                        .eq(SysAiConfig::getGroupName, groupName)
                        .eq(SysAiConfig::getDeleted, 0));

        Map<String, String> resetMap = group.stream()
                .collect(Collectors.toMap(SysAiConfig::getConfigKey, SysAiConfig::getDefaultValue));

        for (SysAiConfig cfg : group) {
            cfg.setConfigValue(cfg.getDefaultValue());
            aiConfigMapper.updateById(cfg);
        }

        aiConfigHolder.updateBatch(resetMap);
        if ("llm".equals(groupName)) {
            aiConfigHolder.refreshLlmModel();
        }
        log.info("[AiConfig] 已重置分组 [{}] 的 {} 项配置为默认值", groupName, group.size());
    }

    @Override
    @Transactional
    public void resetAll() {
        List<SysAiConfig> all = aiConfigMapper.selectList(
                new LambdaQueryWrapper<SysAiConfig>().eq(SysAiConfig::getDeleted, 0));

        Map<String, String> resetMap = all.stream()
                .collect(Collectors.toMap(SysAiConfig::getConfigKey, SysAiConfig::getDefaultValue));

        for (SysAiConfig cfg : all) {
            cfg.setConfigValue(cfg.getDefaultValue());
            aiConfigMapper.updateById(cfg);
        }

        aiConfigHolder.updateBatch(resetMap);
        aiConfigHolder.refreshLlmModel();
        log.info("[AiConfig] 已重置全部 {} 项配置为默认值", all.size());
    }
}
