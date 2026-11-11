package com.simon.MindCrew.service.dataqa;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 问数 · 指标目录（唯一允许集合）
 *
 * 设计契约见 docs/design-data-qa.md 第 2 节：
 * 白名单共 9 个指标，前端指标清单和 LLM 解析都以此为准，绝不接受目录之外的 key。
 *
 * 说明：dimensions 用于校验 LLM 返回的 dimension，只有命中才采用，
 * 否则回落到该指标的默认维度。dimension 只影响展示语义，不参与 SQL 生成。
 */
public final class MetricCatalog {

    /**
     * 指标元数据。
     *
     * @param key        指标唯一 key（接口返回、前端传参都用它）
     * @param name       中文名称
     * @param description 一句话描述
     * @param chartType  默认图表类型：line / bar / pie / table
     * @param dimensions 允许的维度白名单（第 1 个为默认维度）
     * @param example    示例问题（前端 chip 点击填入）
     */
    public record Metric(String key,
                         String name,
                         String description,
                         String chartType,
                         List<String> dimensions,
                         String example) {
    }

    /** 9 个指标（顺序即指标清单展示顺序）。 */
    private static final List<Metric> METRICS = List.of(
            new Metric("question_count", "每日提问量", "按天统计用户提问条数",
                    "line", List.of("day"), "最近7天每天提问量"),
            new Metric("active_users", "每日活跃用户", "按天统计去重活跃用户数",
                    "line", List.of("day"), "最近7天每天有多少活跃用户"),
            new Metric("token_usage", "每日 Token 用量", "按天汇总输入/输出 Token",
                    "line", List.of("day"), "最近7天每天消耗多少token"),
            new Metric("cost_by_model", "模型成本分布", "按模型统计成本与 Token",
                    "bar", List.of("model_name"), "最近30天每个模型的成本"),
            new Metric("latency_trend", "平均响应时间趋势", "按天统计平均响应耗时",
                    "line", List.of("day"), "最近30天平均响应时间"),
            new Metric("feedback_ratio", "反馈满意度", "按反馈类型统计条数与占比",
                    "pie", List.of("feedback"), "最近30天用户反馈满意率"),
            new Metric("kb_ranking", "知识库使用排行", "按知识库统计被引用次数",
                    "bar", List.of("kb_id"), "最近30天哪个知识库被用得最多"),
            new Metric("top_queries", "热门问题 TopN", "统计被问得最多的问题",
                    "table", List.of("content"), "最近30天提问最多的10个问题"),
            new Metric("usage_by_day", "每日成本", "按天汇总用量成本",
                    "line", List.of("stat_date"), "最近30天每天的成本")
    );

    /** key -> Metric，只读索引。 */
    private static final Map<String, Metric> BY_KEY;

    static {
        Map<String, Metric> map = new LinkedHashMap<>();
        for (Metric metric : METRICS) {
            map.put(metric.key(), metric);
        }
        BY_KEY = Map.copyOf(map);
    }

    private MetricCatalog() {
    }

    /** 全部指标（顺序固定）。 */
    public static List<Metric> all() {
        return METRICS;
    }

    /** 按 key 取指标，不存在返回 null。 */
    public static Metric get(String key) {
        return key == null ? null : BY_KEY.get(key);
    }

    /** key 是否在白名单内。 */
    public static boolean contains(String key) {
        return key != null && BY_KEY.containsKey(key);
    }

    /**
     * 指标清单视图（GET /api/v2/data-qa/metrics 的 data）。
     * 只暴露契约约定的 5 个字段，dimensions 属于内部实现细节。
     */
    public static List<Map<String, Object>> describeAll() {
        List<Map<String, Object>> list = new ArrayList<>(METRICS.size());
        for (Metric metric : METRICS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", metric.key());
            item.put("name", metric.name());
            item.put("description", metric.description());
            item.put("chartType", metric.chartType());
            item.put("example", metric.example());
            list.add(item);
        }
        return list;
    }
}
