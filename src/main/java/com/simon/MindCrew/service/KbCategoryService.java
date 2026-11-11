package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbCategory;
import com.simon.MindCrew.mapper.KbCategoryMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 分类字典服务。
 *
 * 启动时把所有 enabled 字典加载进内存缓存（变更频率极低）。
 * 暴露 byCode(code) / list() / refresh() 三个核心 API。
 *
 * DocumentClassifierService 调用 list() 拿到所有候选 code 喂给 LLM。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbCategoryService {

    private final KbCategoryMapper mapper;

    private final AtomicReference<List<KbCategory>> cache = new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<Map<String, KbCategory>> byCode = new AtomicReference<>(Collections.emptyMap());

    @PostConstruct
    public void init() { refresh(); }

    public synchronized void refresh() {
        try {
            List<KbCategory> all = mapper.selectList(
                    new LambdaQueryWrapper<KbCategory>()
                            .eq(KbCategory::getEnabled, 1)
                            .orderByAsc(KbCategory::getSortOrder)
            );
            cache.set(all);
            Map<String, KbCategory> map = new LinkedHashMap<>();
            for (KbCategory c : all) map.put(c.getCode(), c);
            byCode.set(map);
            log.info("[KbCategory] 加载分类字典 · {} 项", all.size());
        } catch (Exception e) {
            cache.set(Collections.emptyList());
            byCode.set(Collections.emptyMap());
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("doesn't exist") || msg.contains("kb_category")) {
                log.warn("[KbCategory] 表 kb_category 不存在，跳过加载（请执行 sql/kb-category-schema.sql）");
            } else {
                log.warn("[KbCategory] 加载失败: {}", msg);
            }
        }
    }

    public List<KbCategory> list() {
        return cache.get();
    }

    public KbCategory byCode(String code) {
        if (code == null) return null;
        return byCode.get().get(code);
    }

    /** 返回所有启用的分类 code 列表，供 LLM Prompt 使用 */
    public List<String> codes() {
        List<String> out = new ArrayList<>();
        for (KbCategory c : cache.get()) out.add(c.getCode());
        return out;
    }

    /** 用于 admin 增删改 */
    public Long create(KbCategory c) {
        if (c.getSortOrder() == null) c.setSortOrder(100);
        if (c.getEnabled() == null)   c.setEnabled(1);
        mapper.insert(c);
        refresh();
        return c.getId();
    }

    public void update(KbCategory c) {
        mapper.updateById(c);
        refresh();
    }

    public void delete(Long id) {
        mapper.deleteById(id);
        refresh();
    }
}
