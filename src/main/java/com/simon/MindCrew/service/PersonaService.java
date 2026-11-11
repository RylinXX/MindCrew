package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.SystemPersona;
import com.simon.MindCrew.mapper.SystemPersonaMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Soul 人格管理服务。
 *
 * 设计要点：
 *   1. 启动时从 DB 全量加载到内存 → 后续读操作零 IO
 *   2. 增删改后调 refresh() 重新加载
 *   3. 默认人格用 AtomicReference 保证读取无锁
 *   4. 反讨好规则段是"全局开关"——任何 persona 只要 anti_sycophancy=1 就会追加
 *
 * 提供给上层使用的核心方法：
 *   buildSystemPrompt(personaId)  →  完整可注入到 ChatClient 的 system prompt
 *   getDefault()                  →  当前默认人格
 *   buildDefaultSystemPrompt()    →  默认人格的完整 prompt
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonaService {

    private final SystemPersonaMapper personaMapper;

    /** id → persona */
    private final Map<Long, SystemPersona> cache = new ConcurrentHashMap<>();

    /** 名称 → persona（便于 prompt 模板按名引用） */
    private final Map<String, SystemPersona> nameCache = new ConcurrentHashMap<>();

    /** 默认人格的快速引用 */
    private final AtomicReference<SystemPersona> defaultPersonaRef = new AtomicReference<>();

    /**
     * 反讨好规则段。任何启用 anti_sycophancy 的 persona 都会在 system prompt 末尾追加这一段。
     * 这是 Soul 系统的"全局底线"，确保所有人格都不会沦为讨好型 AI。
     */
    public static final String ANTI_SYCOPHANCY_BLOCK = """

            ━━ 真实性底线（不可违反）━━
            1. 不要无条件认同用户。事实有误就明确指出，不要为了让用户开心而附和。
            2. 资料中没有的内容，明确说"资料未涉及"，禁止编造让用户满意的答案。
            3. 当用户陈述明显错误时，礼貌但坚定地纠正，并给出依据。
            4. 不使用"很棒的问题""您说得对极了"这类没有信息量的恭维话。
            5. 当你不确定时，明确说"我不确定"，不要伪装成知道。
            6. 用户决策方向有风险时，主动指出风险，不为了对方情绪就回避。
            """;

    // ─────────────────────────────────────────────
    // 启动时初始化缓存
    // ─────────────────────────────────────────────
    @PostConstruct
    public void init() {
        refresh();
    }

    /** 从 DB 全量重载缓存。增删改后调用。 */
    public synchronized void refresh() {
        List<SystemPersona> all = personaMapper.selectList(
                new LambdaQueryWrapper<SystemPersona>()
                        .eq(SystemPersona::getEnabled, 1)
                        .orderByAsc(SystemPersona::getSortOrder)
        );

        cache.clear();
        nameCache.clear();
        SystemPersona def = null;
        for (SystemPersona p : all) {
            cache.put(p.getId(), p);
            if (p.getName() != null) nameCache.put(p.getName(), p);
            if (def == null && Integer.valueOf(1).equals(p.getIsDefault())) def = p;
        }
        // 没有显式默认时，取排序第一个
        if (def == null && !all.isEmpty()) def = all.get(0);
        defaultPersonaRef.set(def);

        log.info("[PersonaService] 已加载 {} 个 persona，默认: {}",
                all.size(), def == null ? "(none)" : def.getName());
    }

    // ─────────────────────────────────────────────
    // 读取
    // ─────────────────────────────────────────────
    public List<SystemPersona> listAll() {
        return cache.values().stream()
                .sorted((a, b) -> Integer.compare(
                        a.getSortOrder() == null ? 0 : a.getSortOrder(),
                        b.getSortOrder() == null ? 0 : b.getSortOrder()))
                .toList();
    }

    public SystemPersona getById(Long id) {
        return id == null ? null : cache.get(id);
    }

    public SystemPersona getByName(String name) {
        return name == null ? null : nameCache.get(name);
    }

    public SystemPersona getDefault() {
        return defaultPersonaRef.get();
    }

    // ─────────────────────────────────────────────
    // Prompt 组装（上层调用方主要用这两个方法）
    // ─────────────────────────────────────────────

    /**
     * 根据 persona ID 构建完整的 system prompt（含反讨好规则）。
     * @param personaId 为 null 时回退到默认 persona
     * @return 完整 prompt 字符串；若没有任何 persona 配置，返回 ""
     */
    public String buildSystemPrompt(Long personaId) {
        SystemPersona p = (personaId == null) ? defaultPersonaRef.get() : cache.get(personaId);
        if (p == null) p = defaultPersonaRef.get();
        if (p == null) return "";
        return buildSystemPrompt(p);
    }

    /** 拿默认 persona 的完整 prompt */
    public String buildDefaultSystemPrompt() {
        return buildSystemPrompt((Long) null);
    }

    /** 给定 persona 实例构建 prompt（含反讨好块）。 */
    public String buildSystemPrompt(SystemPersona p) {
        if (p == null) return "";
        StringBuilder sb = new StringBuilder();
        if (p.getSystemPrompt() != null) sb.append(p.getSystemPrompt().trim());
        if (Integer.valueOf(1).equals(p.getAntiSycophancy())) {
            sb.append(ANTI_SYCOPHANCY_BLOCK);
        }
        return sb.toString();
    }

    /**
     * 取 persona 的温度参数（用于 ChatClient.options）。
     * 没有指定 personaId 或 persona 缺失 temperature 时返回 null（调用方用全局默认）。
     */
    public Double resolveTemperature(Long personaId) {
        SystemPersona p = (personaId == null) ? defaultPersonaRef.get() : cache.get(personaId);
        if (p == null || p.getTemperature() == null) return null;
        return p.getTemperature().doubleValue();
    }

    // ─────────────────────────────────────────────
    // 写入（控制台用）
    // ─────────────────────────────────────────────
    public Long create(SystemPersona p) {
        if (p.getEnabled() == null) p.setEnabled(1);
        if (p.getIsDefault() == null) p.setIsDefault(0);
        if (p.getAntiSycophancy() == null) p.setAntiSycophancy(1);
        if (p.getSortOrder() == null) p.setSortOrder(100);
        personaMapper.insert(p);
        refresh();
        return p.getId();
    }

    public void update(SystemPersona p) {
        personaMapper.updateById(p);
        refresh();
    }

    public void delete(Long id) {
        personaMapper.deleteById(id);
        refresh();
    }

    /** 设为默认（同时把其他全部取消默认） */
    public synchronized void setDefault(Long id) {
        // 清空所有默认
        SystemPersona reset = new SystemPersona();
        reset.setIsDefault(0);
        personaMapper.update(reset, new LambdaQueryWrapper<SystemPersona>()
                .eq(SystemPersona::getIsDefault, 1));
        // 设新默认
        SystemPersona target = personaMapper.selectById(id);
        if (target != null) {
            target.setIsDefault(1);
            personaMapper.updateById(target);
        }
        refresh();
    }
}
