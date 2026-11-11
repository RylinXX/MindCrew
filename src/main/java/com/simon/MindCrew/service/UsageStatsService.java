package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.simon.MindCrew.entity.ModelPricing;
import com.simon.MindCrew.entity.UsageDaily;
import com.simon.MindCrew.mapper.ModelPricingMapper;
import com.simon.MindCrew.mapper.UsageDailyMapper;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用量统计核心服务 · 任务 13
 *
 * 职责：
 *   - 启动加载 model_pricing 字典到内存
 *   - 提供 calcCost(model, inputTokens, outputTokens) 计算单次成本
 *   - recordOne(userId, model, inputTokens, outputTokens) 入 usage_daily（按天聚合）
 *   - 各类 dashboard 查询（个人 / 部门 / 全公司）
 *
 * 务实约束：
 *   - 入库操作 @Async，不阻塞主问答流程
 *   - usage_daily UNIQUE (user_id, stat_date) 通过 INSERT ... ON DUPLICATE KEY UPDATE
 *     在 mybatis-plus 层用 selectOne + update/insert，并发用 SQL setSql 自增防丢失
 *   - 没有模型定价配置时 cost=0（warn 日志，不抛异常）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsageStatsService {

    private final ModelPricingMapper pricingMapper;
    private final UsageDailyMapper dailyMapper;

    /** 模型定价缓存（启动加载，热修改可调 refreshPricing） */
    private final AtomicReference<Map<String, ModelPricing>> pricingCache =
            new AtomicReference<>(new HashMap<>());

    @PostConstruct
    public void init() {
        refreshPricing();
    }

    public synchronized void refreshPricing() {
        try {
            List<ModelPricing> all = pricingMapper.selectList(
                    new LambdaQueryWrapper<ModelPricing>().eq(ModelPricing::getEnabled, 1));
            Map<String, ModelPricing> m = new HashMap<>();
            for (ModelPricing p : all) m.put(p.getModelName(), p);
            pricingCache.set(m);
            log.info("[Usage] 加载模型定价 {} 项", m.size());
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("doesn't exist") || msg.contains("model_pricing")) {
                log.warn("[Usage] 表 model_pricing 不存在 · 请跑 sql/usage-stats-schema.sql");
            } else {
                log.warn("[Usage] 加载定价失败: {}", msg);
            }
        }
    }

    // ─────────────────────────────────────────────
    // 单次成本计算
    // ─────────────────────────────────────────────

    @Data
    public static class CostResult {
        private final BigDecimal cost;
        private final String matchedModel;       // 实际命中的 pricing model 名（找不到时为 null）
        public CostResult(BigDecimal cost, String matchedModel) { this.cost = cost; this.matchedModel = matchedModel; }
    }

    /**
     * 按 token 计算成本（chat / vision / embedding）
     * 务实兜底：精确匹配优先 · 找不到时按前缀模糊匹配（qwen-xxx → qwen-plus 默认）
     */
    public CostResult calcCost(String modelName, int inputTokens, int outputTokens) {
        if (modelName == null) return new CostResult(BigDecimal.ZERO, null);

        Map<String, ModelPricing> cache = pricingCache.get();
        if (cache.isEmpty()) {
            // 启动阶段偶发查询失败时自愈：重新加载一次
            refreshPricing();
            cache = pricingCache.get();
        }
        if (cache.isEmpty()) {
            log.warn("[Usage] model_pricing 缓存为空 · 请确认已执行 sql/usage-stats-schema.sql");
            return new CostResult(BigDecimal.ZERO, null);
        }

        // 1) 精确匹配
        ModelPricing p = cache.get(modelName);
        String matched = modelName;

        // 2) 兜底：前缀模糊匹配（qwen-2.5 / qwen-vl-xxx / gpt-4-* 等）
        if (p == null) {
            String lower = modelName.toLowerCase();
            if      (lower.startsWith("qwen-vl")) { p = cache.get("qwen-vl-max");     matched = "qwen-vl-max"; }
            else if (lower.startsWith("qwen"))    { p = cache.get("qwen-plus");      matched = "qwen-plus"; }
            else if (lower.startsWith("gpt-4o-mini")) { p = cache.get("gpt-4o-mini"); matched = "gpt-4o-mini"; }
            else if (lower.startsWith("gpt-4"))   { p = cache.get("gpt-4o");         matched = "gpt-4o"; }
            else if (lower.startsWith("deepseek")) { p = cache.get("deepseek-chat"); matched = "deepseek-chat"; }
            else if (lower.contains("embedding")) { p = cache.get("text-embedding-v3"); matched = "text-embedding-v3"; }
        }

        if (p == null) {
            log.warn("[Usage] 未找到模型定价 · model={} · cost=0 · 已加载定价表={}",
                    modelName, cache.keySet());
            return new CostResult(BigDecimal.ZERO, null);
        }

        BigDecimal cost = BigDecimal.ZERO;
        if (p.getInputPricePer1k() != null && inputTokens > 0) {
            cost = cost.add(p.getInputPricePer1k()
                    .multiply(BigDecimal.valueOf(inputTokens))
                    .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP));
        }
        if (p.getOutputPricePer1k() != null && outputTokens > 0) {
            cost = cost.add(p.getOutputPricePer1k()
                    .multiply(BigDecimal.valueOf(outputTokens))
                    .divide(BigDecimal.valueOf(1000), 8, RoundingMode.HALF_UP));
        }
        BigDecimal finalCost = cost.setScale(6, RoundingMode.HALF_UP);
        log.debug("[Usage] calcCost · model={}→{} in={} out={} cost={}",
                modelName, matched, inputTokens, outputTokens, finalCost.toPlainString());
        return new CostResult(finalCost, matched);
    }

    /** 按调用次数计费（rerank / ocr 等） */
    public CostResult calcUnitCost(String modelName, int times) {
        if (modelName == null || times <= 0) return new CostResult(BigDecimal.ZERO, null);
        ModelPricing p = pricingCache.get().get(modelName);
        if (p == null || p.getUnitPrice() == null) return new CostResult(BigDecimal.ZERO, null);
        BigDecimal cost = p.getUnitPrice()
                .multiply(BigDecimal.valueOf(times))
                .setScale(6, RoundingMode.HALF_UP);
        return new CostResult(cost, modelName);
    }

    /** 按音频秒数计费（asr） */
    public CostResult calcAsrCost(String modelName, int audioSeconds) {
        if (modelName == null || audioSeconds <= 0) return new CostResult(BigDecimal.ZERO, null);
        ModelPricing p = pricingCache.get().get(modelName);
        if (p == null || p.getUnitPrice() == null) return new CostResult(BigDecimal.ZERO, null);
        BigDecimal cost = p.getUnitPrice()
                .multiply(BigDecimal.valueOf(audioSeconds))
                .setScale(6, RoundingMode.HALF_UP);
        return new CostResult(cost, modelName);
    }

    // ─────────────────────────────────────────────
    // 异步入账
    // ─────────────────────────────────────────────

    /**
     * 一次 chat / vision 调用结束后调用此方法记账
     * 异步执行，不阻塞主流程
     */
    @Async
    public void recordChatAsync(Long userId, String modelName, int inputTokens, int outputTokens, boolean goldenHit) {
        if (userId == null) return;
        CostResult cr = calcCost(modelName, inputTokens, outputTokens);
        log.info("[Usage] 记账 chat · user={} model={} in={} out={} cost=¥{} golden={}",
                userId, modelName, inputTokens, outputTokens, cr.cost.toPlainString(), goldenHit);
        upsertDaily(userId, LocalDate.now(),
                /*chat*/ 1, inputTokens, outputTokens, 0,
                /*vision*/ 0, 0, cr.cost, goldenHit ? 1 : 0);
    }

    @Async
    public void recordVisionAsync(Long userId, int imageCount, int totalTokens) {
        if (userId == null) return;
        CostResult cr = calcCost("qwen-vl-max", totalTokens / 2, totalTokens / 2);
        upsertDaily(userId, LocalDate.now(),
                0, 0, 0, 0,
                imageCount, 0, cr.cost, 0);
    }

    @Async
    public void recordEmbeddingAsync(Long userId, int tokens) {
        if (userId == null) return;
        CostResult cr = calcCost("text-embedding-v3", tokens, 0);
        upsertDaily(userId, LocalDate.now(),
                0, 0, 0, tokens,
                0, 0, cr.cost, 0);
    }

    @Async
    public void recordAsrAsync(Long userId, int audioSeconds) {
        if (userId == null) return;
        CostResult cr = calcAsrCost("paraformer-v2", audioSeconds);
        upsertDaily(userId, LocalDate.now(),
                0, 0, 0, 0,
                0, audioSeconds, cr.cost, 0);
    }

    /**
     * Upsert · 一天一行；用原子 SQL 自增防并发丢失
     */
    @Transactional
    protected void upsertDaily(Long userId, LocalDate date,
                                int chat, int input, int output, int embedding,
                                int vision, int asrSec, BigDecimal cost, int goldenHit) {
        UsageDaily existing = dailyMapper.selectOne(new LambdaQueryWrapper<UsageDaily>()
                .eq(UsageDaily::getUserId, userId)
                .eq(UsageDaily::getStatDate, date)
                .last("LIMIT 1"));
        if (existing == null) {
            try {
                UsageDaily u = new UsageDaily();
                u.setUserId(userId);
                u.setStatDate(date);
                u.setChatCount(chat);
                u.setInputTokens((long) input);
                u.setOutputTokens((long) output);
                u.setEmbeddingTokens((long) embedding);
                u.setVisionCalls(vision);
                u.setAsrSeconds(asrSec);
                u.setCostCny(cost == null ? BigDecimal.ZERO : cost);
                u.setGoldenHitCount(goldenHit);
                dailyMapper.insert(u);
                return;
            } catch (org.springframework.dao.DuplicateKeyException dup) {
                // 并发场景：另一个事务已插入 · 走 update 分支
            } catch (Exception e) {
                log.warn("[Usage] insert 失败 user={} date={}: {}", userId, date, e.getMessage());
                return;
            }
        }
        // 原子 SQL 自增
        try {
            LambdaUpdateWrapper<UsageDaily> w = new LambdaUpdateWrapper<UsageDaily>()
                    .eq(UsageDaily::getUserId, userId)
                    .eq(UsageDaily::getStatDate, date);
            if (chat > 0)      w.setSql("chat_count = chat_count + " + chat);
            if (input > 0)     w.setSql("input_tokens = input_tokens + " + input);
            if (output > 0)    w.setSql("output_tokens = output_tokens + " + output);
            if (embedding > 0) w.setSql("embedding_tokens = embedding_tokens + " + embedding);
            if (vision > 0)    w.setSql("vision_calls = vision_calls + " + vision);
            if (asrSec > 0)    w.setSql("asr_seconds = asr_seconds + " + asrSec);
            if (cost != null && cost.signum() > 0)
                w.setSql("cost_cny = cost_cny + " + cost.toPlainString());
            if (goldenHit > 0) w.setSql("golden_hit_count = golden_hit_count + " + goldenHit);
            dailyMapper.update(null, w);
        } catch (Exception e) {
            log.warn("[Usage] update 失败 user={} date={}: {}", userId, date, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 查询 · 管理员钻取（13.6）
    // ─────────────────────────────────────────────

    /** 单用户在 [from, to] 区间的所有日记录 */
    public List<UsageDaily> listUserUsage(Long userId, LocalDate from, LocalDate to) {
        return dailyMapper.selectList(new LambdaQueryWrapper<UsageDaily>()
                .eq(UsageDaily::getUserId, userId)
                .ge(from != null, UsageDaily::getStatDate, from)
                .le(to != null,   UsageDaily::getStatDate, to)
                .orderByDesc(UsageDaily::getStatDate));
    }

    /** 单用户区间汇总（一行 summary） */
    public Map<String, Object> summarizeUser(Long userId, LocalDate from, LocalDate to) {
        List<UsageDaily> list = listUserUsage(userId, from, to);
        long chatCount = 0, inToks = 0, outToks = 0, embToks = 0, golden = 0;
        long vlCalls = 0, asrSec = 0;
        BigDecimal cost = BigDecimal.ZERO;
        for (UsageDaily u : list) {
            chatCount += orZero(u.getChatCount());
            inToks    += orZeroL(u.getInputTokens());
            outToks   += orZeroL(u.getOutputTokens());
            embToks   += orZeroL(u.getEmbeddingTokens());
            vlCalls   += orZero(u.getVisionCalls());
            asrSec    += orZero(u.getAsrSeconds());
            golden    += orZero(u.getGoldenHitCount());
            if (u.getCostCny() != null) cost = cost.add(u.getCostCny());
        }
        Map<String, Object> out = new HashMap<>();
        out.put("chatCount", chatCount);
        out.put("inputTokens",  inToks);
        out.put("outputTokens", outToks);
        out.put("embeddingTokens", embToks);
        out.put("visionCalls", vlCalls);
        out.put("asrSeconds",  asrSec);
        out.put("goldenHitCount", golden);
        out.put("costCny",     cost.setScale(4, RoundingMode.HALF_UP));
        out.put("dayList",     list);
        return out;
    }

    /** 当月所有用户 cost 排行（top N · 13.6 管理员看板用） */
    public List<UsageDaily> topUsersThisMonth(int topN) {
        LocalDate firstDay = LocalDate.now().withDayOfMonth(1);
        return dailyMapper.selectList(new LambdaQueryWrapper<UsageDaily>()
                .ge(UsageDaily::getStatDate, firstDay)
                .orderByDesc(UsageDaily::getCostCny)
                .last("LIMIT " + Math.max(1, Math.min(topN, 100))));
    }

    private static long orZeroL(Long v) { return v == null ? 0 : v; }
    private static int  orZero(Integer v) { return v == null ? 0 : v; }
}
