package com.simon.MindCrew.service.dataqa;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.entity.UsageDaily;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.mapper.UsageDailyMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AI 问数 · 安全指标问答
 *
 * 安全模型（见 docs/design-data-qa.md）：
 *   1. LLM 只做「自然语言 → 白名单指标 key + 参数」的意图解析，不生成、不执行任何 SQL；
 *   2. 所有查询均为本类内的固定模板（QueryWrapper），用户输入不拼接进 SQL；
 *   3. LLM 解析失败/异常 → 关键词兜底；仍不支持 → 400 + 指标列表；
 *   4. 只读，不写审计；days 1~90、limit 1~50 越界钳制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataQaService {

    private final AiConfigHolder aiConfigHolder;
    private final QaMessageMapper qaMessageMapper;
    private final QaConversationMapper qaConversationMapper;
    private final UsageDailyMapper usageDailyMapper;
    private final KbKnowledgeBaseMapper kbKnowledgeBaseMapper;

    /** 天数范围（契约固定） */
    private static final int DAYS_MIN = 1;
    private static final int DAYS_MAX = 90;
    private static final int DAYS_DEFAULT = 7;

    /** TopN 范围（契约固定） */
    private static final int LIMIT_MIN = 1;
    private static final int LIMIT_MAX = 50;
    private static final int LIMIT_DEFAULT = 10;

    /** kb_ranking / top_queries 内存聚合的最大扫描行数 */
    private static final int MAX_SCAN_ROWS = 5000;

    private static final String SYSTEM_PROMPT = """
            你是 MindCrew 的「数据指标解析器」。
            你的唯一任务：把用户的数据分析问题，映射到下方固定的指标 key，并输出严格 JSON。

            规则：
            1. 只输出一个 JSON 对象，不要解释、不要 markdown 代码块、不要多余文字。
            2. metric 只能从下列 key 中选；与所有指标都无关时输出 null。
            3. days 表示最近天数，1-90 的整数；用户未提时间范围时用 7。
            4. limit 表示排行/榜单条数，1-50 的整数；用户未提条数时用 10。
            5. dimension 只能按下面对应取值填写。

            可选指标：
            - question_count：每日提问量，按天；dimension=day
            - active_users：每日活跃用户，按天；dimension=day
            - token_usage：每日 Token 用量，按天；dimension=day
            - cost_by_model：模型成本分布，按模型；dimension=model_name
            - latency_trend：平均响应时间趋势，按天；dimension=day
            - feedback_ratio：反馈满意度，按反馈类型；dimension=feedback
            - kb_ranking：知识库使用排行，按知识库；dimension=kb_id
            - top_queries：热门问题 TopN，按问题内容；dimension=content
            - usage_by_day：每日成本，按天；dimension=stat_date

            输出示例：
            {"metric":"cost_by_model","days":30,"limit":10,"dimension":"model_name"}
            """;

    /**
     * 关键词兜底规则：按顺序匹配，先命中先返回。
     * 顺序刻意把更具体的问法（热门问题、知识库、反馈等）放在泛化的「问题/成本」之前。
     */
    private static final List<KeywordRule> KEYWORD_RULES = List.of(
            new KeywordRule("top_queries", List.of("热门", "最多的问题", "提问最多", "问得最多", "被问最多")),
            new KeywordRule("kb_ranking", List.of("知识库", "文库", "kb")),
            new KeywordRule("feedback_ratio", List.of("满意", "反馈", "点踩", "差评")),
            new KeywordRule("latency_trend", List.of("响应", "延迟", "耗时", "速度")),
            new KeywordRule("usage_by_day", List.of("每日成本", "每天成本", "每日花费", "每天花费",
                    "按天成本", "每天花", "每日花", "花多少")),
            new KeywordRule("token_usage", List.of("token", "令牌")),
            new KeywordRule("question_count", List.of("问题", "提问", "问答", "问了多少")),
            new KeywordRule("active_users", List.of("活跃", "用户")),
            new KeywordRule("cost_by_model", List.of("成本", "费用", "花费", "多少钱"))
    );

    // ======================== 对外入口 ========================

    /**
     * 回答一个自然语言数据问题。
     *
     * @param question    用户问题（必填）
     * @param requestDays 显式指定的天数（可空；非空时覆盖 LLM 解析结果，越界钳制）
     * @return 契约约定的响应体（question/metric/metricName/interpreted/answer/columns/rows/chart/elapsedMs）
     */
    public Map<String, Object> ask(String question, Integer requestDays) {
        long startMs = System.currentTimeMillis();
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "问题不能为空");
        }

        Intent intent = resolveIntent(q);
        if (intent == null || !MetricCatalog.contains(intent.metric())) {
            throw unsupportedMetricException();
        }

        MetricCatalog.Metric metric = MetricCatalog.get(intent.metric());

        // 参数钳制：显式请求的 days 优先于 LLM 解析结果
        int days = requestDays != null
                ? clamp(requestDays, DAYS_MIN, DAYS_MAX, DAYS_DEFAULT)
                : clamp(intent.days(), DAYS_MIN, DAYS_MAX, DAYS_DEFAULT);
        int limit = clamp(intent.limit(), LIMIT_MIN, LIMIT_MAX, LIMIT_DEFAULT);

        // dimension 只接受指标白名单内的值，否则回落到默认维度；不参与 SQL
        String dimension = intent.dimension() != null && metric.dimensions().contains(intent.dimension())
                ? intent.dimension()
                : metric.dimensions().get(0);

        Series series;
        String answer;
        switch (metric.key()) {
            case "question_count" -> {
                series = questionCount(days);
                answer = answerQuestionCount(days, series.rows());
            }
            case "active_users" -> {
                series = activeUsers(days);
                answer = answerActiveUsers(days, series.rows());
            }
            case "token_usage" -> {
                series = tokenUsage(days);
                answer = answerTokenUsage(days, series.rows());
            }
            case "cost_by_model" -> {
                series = costByModel(days);
                answer = answerCostByModel(days, series.rows());
            }
            case "latency_trend" -> {
                series = latencyTrend(days);
                answer = answerLatencyTrend(days, series.rows());
            }
            case "feedback_ratio" -> {
                series = feedbackRatio(days);
                answer = answerFeedbackRatio(days, series.rows());
            }
            case "kb_ranking" -> {
                series = kbRanking(days, limit);
                answer = answerKbRanking(days, series.rows());
            }
            case "top_queries" -> {
                series = topQueries(days, limit);
                answer = answerTopQueries(days, series.rows());
            }
            case "usage_by_day" -> {
                series = usageByDay(days);
                answer = answerUsageByDay(days, series.rows());
            }
            default -> throw unsupportedMetricException();
        }

        Map<String, Object> interpreted = new LinkedHashMap<>();
        interpreted.put("days", days);
        interpreted.put("limit", limit);
        interpreted.put("dimension", dimension);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("question", q);
        response.put("metric", metric.key());
        response.put("metricName", metric.name());
        response.put("interpreted", interpreted);
        response.put("answer", answer);
        response.put("columns", series.columns());
        response.put("rows", series.rows());
        response.put("chart", series.chart());
        response.put("elapsedMs", System.currentTimeMillis() - startMs);
        return response;
    }

    // ======================== 意图解析 ========================

    private Intent resolveIntent(String question) {
        Intent byLlm = parseIntentByLlm(question);
        if (byLlm != null) {
            return byLlm;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        for (KeywordRule rule : KEYWORD_RULES) {
            for (String keyword : rule.keywords()) {
                if (lower.contains(keyword)) {
                    MetricCatalog.Metric metric = MetricCatalog.get(rule.metric());
                    if (metric == null) {
                        continue;
                    }
                    log.info("[DataQa] LLM 未解析成功，关键词兜底命中: question={}, metric={}", question, metric.key());
                    return new Intent(metric.key(), null, null, metric.dimensions().get(0));
                }
            }
        }
        return null;
    }

    /** LLM 意图解析：严格 JSON；任何异常都返回 null 交给关键词兜底，不影响主流程。 */
    private Intent parseIntentByLlm(String question) {
        try {
            ChatClient client = ChatClient.builder(aiConfigHolder.getChatModel())
                    .defaultSystem(SYSTEM_PROMPT)
                    .build();
            String raw = client.prompt()
                    .user("用户问题：" + question)
                    .call()
                    .content();
            if (raw == null || raw.isBlank()) {
                return null;
            }
            int start = raw.indexOf('{');
            int end = raw.lastIndexOf('}');
            if (start < 0 || end <= start) {
                log.warn("[DataQa] LLM 输出不含 JSON 对象: {}", abbreviate(raw, 200));
                return null;
            }
            JSONObject json = JSON.parseObject(raw.substring(start, end + 1));
            if (json == null) {
                return null;
            }
            String metric = json.getString("metric");
            if (!MetricCatalog.contains(metric)) {
                log.info("[DataQa] LLM 返回非白名单指标: {}", metric);
                return null;
            }
            return new Intent(metric, toInteger(json.get("days")), toInteger(json.get("limit")),
                    json.getString("dimension"));
        } catch (Exception e) {
            // 模型不可用 / 超时 / JSON 非法 / 非白名单指标 → 关键词兜底
            log.warn("[DataQa] LLM 意图解析失败，走关键词兜底: {}", e.getMessage());
            return null;
        }
    }

    private static BusinessException unsupportedMetricException() {
        StringBuilder message = new StringBuilder("暂不支持该指标，可问：");
        List<MetricCatalog.Metric> metrics = MetricCatalog.all();
        for (int i = 0; i < metrics.size(); i++) {
            if (i > 0) {
                message.append("、");
            }
            message.append(metrics.get(i).name()).append('(').append(metrics.get(i).key()).append(')');
        }
        return new BusinessException(ResultCode.PARAM_ERROR.getCode(), message.toString());
    }

    // ======================== 指标查询（固定模板） ========================

    /** 每日提问量：qa_message(role=user) 按天计数。 */
    private Series questionCount(int days) {
        QueryWrapper<QaMessage> wrapper = new QueryWrapper<>();
        wrapper.select("DATE(create_time) AS stat_date", "COUNT(*) AS msg_count")
                .eq("role", "user")
                .ge("create_time", windowStart(days))
                .groupBy("DATE(create_time)")
                .orderByAsc("DATE(create_time)");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> map : qaMessageMapper.selectMaps(wrapper)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", toDateString(cell(map, "stat_date", "statDate")));
            row.put("count", toLong(cell(map, "msg_count", "msgCount", "count")));
            rows.add(row);
        }
        return new Series(columns("date", "日期", "count", "提问量"), rows,
                chart("line", "date", "count", "提问量"));
    }

    /** 每日活跃用户：qa_conversation 去重 user_id 按天计数（@TableLogic 自动过滤已删会话）。 */
    private Series activeUsers(int days) {
        QueryWrapper<QaConversation> wrapper = new QueryWrapper<>();
        wrapper.select("DATE(create_time) AS stat_date", "COUNT(DISTINCT user_id) AS user_count")
                .ge("create_time", windowStart(days))
                .groupBy("DATE(create_time)")
                .orderByAsc("DATE(create_time)");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> map : qaConversationMapper.selectMaps(wrapper)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", toDateString(cell(map, "stat_date", "statDate")));
            row.put("userCount", toLong(cell(map, "user_count", "userCount", "count")));
            rows.add(row);
        }
        return new Series(columns("date", "日期", "userCount", "活跃用户"), rows,
                chart("line", "date", "userCount", "活跃用户"));
    }

    /** 每日 Token 用量：qa_message 汇总 input/output。 */
    private Series tokenUsage(int days) {
        QueryWrapper<QaMessage> wrapper = new QueryWrapper<>();
        wrapper.select("DATE(create_time) AS stat_date",
                        "IFNULL(SUM(input_tokens),0) AS input_tokens",
                        "IFNULL(SUM(output_tokens),0) AS output_tokens")
                .ge("create_time", windowStart(days))
                .groupBy("DATE(create_time)")
                .orderByAsc("DATE(create_time)");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> map : qaMessageMapper.selectMaps(wrapper)) {
            long input = toLong(cell(map, "input_tokens", "inputTokens"));
            long output = toLong(cell(map, "output_tokens", "outputTokens"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", toDateString(cell(map, "stat_date", "statDate")));
            row.put("inputTokens", input);
            row.put("outputTokens", output);
            row.put("totalTokens", input + output);
            rows.add(row);
        }
        return new Series(columns("date", "日期", "inputTokens", "输入 Token",
                "outputTokens", "输出 Token", "totalTokens", "总 Token"), rows,
                chart("line", "date", "totalTokens", "Token"));
    }

    /** 模型成本分布：qa_message group by model_name，sum(cost_cny)。 */
    private Series costByModel(int days) {
        QueryWrapper<QaMessage> wrapper = new QueryWrapper<>();
        wrapper.select("model_name",
                        "IFNULL(SUM(cost_cny),0) AS cost_cny",
                        "(IFNULL(SUM(input_tokens),0) + IFNULL(SUM(output_tokens),0)) AS tokens")
                .ge("create_time", windowStart(days))
                .isNotNull("model_name")
                .groupBy("model_name");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> map : qaMessageMapper.selectMaps(wrapper)) {
            Object rawName = cell(map, "model_name", "modelName", "model");
            String modelName = rawName == null ? "" : String.valueOf(rawName).trim();
            if (modelName.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("modelName", modelName);
            row.put("costCny", toDecimal(cell(map, "cost_cny", "costCny")).setScale(4, RoundingMode.HALF_UP));
            row.put("tokens", toLong(cell(map, "tokens", "token_count", "tokenCount")));
            rows.add(row);
        }
        // 模型数量天然有界（qa_message.distinct model_name），按成本降序展示全量，不做 TopN 截断
        rows.sort((a, b) -> toDecimal(b.get("costCny")).compareTo(toDecimal(a.get("costCny"))));
        return new Series(columns("modelName", "模型", "costCny", "成本(元)", "tokens", "Token"), rows,
                chart("bar", "modelName", "costCny", "成本(元)"));
    }

    /** 平均响应时间趋势：qa_message avg(latency_ms，回退 response_time) 按天。 */
    private Series latencyTrend(int days) {
        QueryWrapper<QaMessage> wrapper = new QueryWrapper<>();
        wrapper.select("DATE(create_time) AS stat_date",
                        "AVG(NULLIF(COALESCE(NULLIF(latency_ms,0), response_time),0)) AS avg_latency")
                .ge("create_time", windowStart(days))
                .groupBy("DATE(create_time)")
                .orderByAsc("DATE(create_time)");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> map : qaMessageMapper.selectMaps(wrapper)) {
            Object rawAvg = cell(map, "avg_latency", "avgLatency");
            if (rawAvg == null) {
                // 当天没有有效耗时样本，不画 0 线造成误导
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", toDateString(cell(map, "stat_date", "statDate")));
            row.put("avgLatencyMs", Math.round(toDouble(rawAvg)));
            rows.add(row);
        }
        return new Series(columns("date", "日期", "avgLatencyMs", "平均响应(ms)"), rows,
                chart("line", "date", "avgLatencyMs", "平均响应(ms)"));
    }

    /** 反馈满意度：qa_message group by feedback（1 有用 / -1 无用 / 0 未评）。 */
    private Series feedbackRatio(int days) {
        QueryWrapper<QaMessage> wrapper = new QueryWrapper<>();
        wrapper.select("feedback", "COUNT(*) AS cnt")
                .ge("create_time", windowStart(days))
                .groupBy("feedback")
                .orderByAsc("feedback");

        long useful = 0L;
        long useless = 0L;
        long unrated = 0L;
        for (Map<String, Object> map : qaMessageMapper.selectMaps(wrapper)) {
            Integer feedback = toInteger(cell(map, "feedback"));
            long count = toLong(cell(map, "cnt", "count"));
            if (feedback == null || feedback == 0) {
                unrated += count;
            } else if (feedback > 0) {
                useful += count;
            } else {
                useless += count;
            }
        }

        long total = useful + useless + unrated;
        List<Map<String, Object>> rows = new ArrayList<>();
        if (total > 0) {
            addFeedbackRow(rows, "有用", useful, total);
            addFeedbackRow(rows, "无用", useless, total);
            addFeedbackRow(rows, "未评价", unrated, total);
        }
        return new Series(columns("label", "反馈", "count", "条数", "ratio", "占比(%)"), rows,
                chart("pie", "label", "count", "反馈条数"));
    }

    private void addFeedbackRow(List<Map<String, Object>> rows, String label, long count, long total) {
        if (count <= 0) {
            return;
        }
        BigDecimal ratio = BigDecimal.valueOf(count)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("count", count);
        row.put("ratio", ratio);
        rows.add(row);
    }

    /**
     * 知识库使用排行：解析 qa_message.retrieval_log 的 selectedKbIds 计数。
     * 必须内存聚合（JSON 字段无法 SQL 分组），最多扫描 5000 行。
     */
    private Series kbRanking(int days, int limit) {
        QueryWrapper<QaMessage> wrapper = new QueryWrapper<>();
        wrapper.select("retrieval_log")
                .ge("create_time", windowStart(days))
                .isNotNull("retrieval_log")
                .orderByDesc("create_time")
                .last("LIMIT " + MAX_SCAN_ROWS);

        Map<Long, Integer> counter = new HashMap<>();
        for (QaMessage message : qaMessageMapper.selectList(wrapper)) {
            collectKbIds(message.getRetrievalLog(), counter);
        }

        List<Map.Entry<Long, Integer>> entries = new ArrayList<>(counter.entrySet());
        entries.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

        List<Long> kbIds = new ArrayList<>();
        for (int i = 0; i < entries.size() && i < limit; i++) {
            kbIds.add(entries.get(i).getKey());
        }
        Map<Long, String> kbNames = loadKbNames(kbIds);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Long kbId : kbIds) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kbId", kbId);
            row.put("kbName", kbNames.getOrDefault(kbId, "知识库#" + kbId));
            row.put("count", counter.getOrDefault(kbId, 0));
            rows.add(row);
        }
        return new Series(columns("kbName", "知识库", "count", "引用次数"), rows,
                chart("bar", "kbName", "count", "引用次数"));
    }

    private void collectKbIds(String retrievalLog, Map<Long, Integer> counter) {
        if (retrievalLog == null || retrievalLog.isBlank()) {
            return;
        }
        try {
            JSONObject json = JSON.parseObject(retrievalLog);
            if (json == null) {
                return;
            }
            JSONArray array = json.getJSONArray("selectedKbIds");
            if (array == null || array.isEmpty()) {
                return;
            }
            Set<Long> messageKbIds = new HashSet<>();
            for (int i = 0; i < array.size(); i++) {
                Long kbId = toLongOrNull(array.get(i));
                if (kbId != null) {
                    messageKbIds.add(kbId);
                }
            }
            for (Long kbId : messageKbIds) {
                counter.merge(kbId, 1, Integer::sum);
            }
        } catch (Exception e) {
            // 单条 JSON 脏数据不影响整体排行
            log.debug("[DataQa] 跳过无法解析的 retrieval_log: {}", e.getMessage());
        }
    }

    /** 知识库名只做展示增强，查询失败时回落到「知识库#id」。 */
    private Map<Long, String> loadKbNames(List<Long> kbIds) {
        Map<Long, String> names = new HashMap<>();
        if (kbIds.isEmpty()) {
            return names;
        }
        try {
            QueryWrapper<KbKnowledgeBase> wrapper = new QueryWrapper<>();
            wrapper.select("id", "name").in("id", kbIds);
            for (KbKnowledgeBase kb : kbKnowledgeBaseMapper.selectList(wrapper)) {
                if (kb.getId() != null && kb.getName() != null) {
                    names.put(kb.getId(), kb.getName());
                }
            }
        } catch (Exception e) {
            log.warn("[DataQa] 知识库名称查询失败，使用 id 兜底: {}", e.getMessage());
        }
        return names;
    }

    /**
     * 热门问题 TopN：qa_message(role=user) 按 content 内存聚合。
     * 必须内存聚合，最多扫描 5000 行。
     */
    private Series topQueries(int days, int limit) {
        QueryWrapper<QaMessage> wrapper = new QueryWrapper<>();
        wrapper.select("content")
                .eq("role", "user")
                .ge("create_time", windowStart(days))
                .isNotNull("content")
                .orderByDesc("create_time")
                .last("LIMIT " + MAX_SCAN_ROWS);

        Map<String, Integer> counter = new HashMap<>();
        for (QaMessage message : qaMessageMapper.selectList(wrapper)) {
            String content = message.getContent();
            if (content == null) {
                continue;
            }
            String key = content.trim();
            if (key.isEmpty()) {
                continue;
            }
            counter.merge(key, 1, Integer::sum);
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(counter.entrySet());
        entries.sort((a, b) -> {
            int byCount = Integer.compare(b.getValue(), a.getValue());
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < entries.size() && i < limit; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("query", abbreviate(entries.get(i).getKey(), 120));
            row.put("count", entries.get(i).getValue());
            rows.add(row);
        }
        return new Series(columns("query", "问题", "count", "次数"), rows,
                chart("table", "query", "count", "提问次数"));
    }

    /** 每日成本：usage_daily group by stat_date，sum(cost_cny)。 */
    private Series usageByDay(int days) {
        QueryWrapper<UsageDaily> wrapper = new QueryWrapper<>();
        wrapper.select("stat_date", "IFNULL(SUM(cost_cny),0) AS cost_cny")
                .ge("stat_date", windowStartDate(days))
                .groupBy("stat_date")
                .orderByAsc("stat_date");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> map : usageDailyMapper.selectMaps(wrapper)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", toDateString(cell(map, "stat_date", "statDate")));
            row.put("costCny", toDecimal(cell(map, "cost_cny", "costCny")).setScale(4, RoundingMode.HALF_UP));
            rows.add(row);
        }
        return new Series(columns("date", "日期", "costCny", "成本(元)"), rows,
                chart("line", "date", "costCny", "成本(元)"));
    }

    // ======================== 一句话解读（模板生成） ========================

    private String answerQuestionCount(int days, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return emptyAnswer(days);
        }
        long total = 0L;
        for (Map<String, Object> row : rows) {
            total += toLong(row.get("count"));
        }
        long avg = Math.round((double) total / days);
        return "最近 " + days + " 天共提问 " + total + " 次，日均 " + avg + " 次。";
    }

    private String answerActiveUsers(int days, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return emptyAnswer(days);
        }
        long sum = 0L;
        long peak = 0L;
        for (Map<String, Object> row : rows) {
            long count = toLong(row.get("userCount"));
            sum += count;
            peak = Math.max(peak, count);
        }
        long avg = Math.round((double) sum / days);
        return "最近 " + days + " 天日均活跃用户 " + avg + " 人，峰值 " + peak + " 人。";
    }

    private String answerTokenUsage(int days, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return emptyAnswer(days);
        }
        long input = 0L;
        long output = 0L;
        for (Map<String, Object> row : rows) {
            input += toLong(row.get("inputTokens"));
            output += toLong(row.get("outputTokens"));
        }
        return "最近 " + days + " 天共消耗 Token " + (input + output) + " 个（输入 " + input
                + " / 输出 " + output + "）。";
    }

    private String answerCostByModel(int days, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return emptyAnswer(days);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            total = total.add(toDecimal(row.get("costCny")));
        }
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            return "最近 " + days + " 天共 " + rows.size() + " 个模型产生调用，累计成本 0 元。";
        }
        Object topName = rows.get(0).get("modelName");
        BigDecimal topCost = toDecimal(rows.get(0).get("costCny"));
        return "最近 " + days + " 天共消耗 " + fmtMoney(total) + " 元，"
                + (topName == null ? "未知模型" : topName) + " 占 " + fmtPercent(topCost, total) + "%。";
    }

    private String answerLatencyTrend(int days, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return emptyAnswer(days);
        }
        double sum = 0D;
        int count = 0;
        for (Map<String, Object> row : rows) {
            long avg = toLong(row.get("avgLatencyMs"));
            if (avg > 0) {
                sum += avg;
                count++;
            }
        }
        if (count == 0) {
            return emptyAnswer(days);
        }
        return "最近 " + days + " 天平均响应时间约 " + Math.round(sum / count) + " ms。";
    }

    private String answerFeedbackRatio(int days, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return emptyAnswer(days);
        }
        long useful = 0L;
        long useless = 0L;
        long unrated = 0L;
        for (Map<String, Object> row : rows) {
            Object label = row.get("label");
            long count = toLong(row.get("count"));
            if ("有用".equals(label)) {
                useful = count;
            } else if ("无用".equals(label)) {
                useless = count;
            } else {
                unrated = count;
            }
        }
        long feedbackTotal = useful + useless;
        if (feedbackTotal == 0) {
            return "最近 " + days + " 天共 " + unrated + " 条反馈记录，均为未评价，暂无有效反馈。";
        }
        String ratio = BigDecimal.valueOf(useful)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(feedbackTotal), 1, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
        return "最近 " + days + " 天收到有效反馈 " + feedbackTotal + " 条，有用 " + useful
                + " 条、无用 " + useless + " 条，满意率 " + ratio + "%。";
    }

    private String answerKbRanking(int days, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return emptyAnswer(days);
        }
        Map<String, Object> top = rows.get(0);
        return "最近 " + days + " 天「" + top.get("kbName") + "」被引用最多，共 "
                + toLong(top.get("count")) + " 次。";
    }

    private String answerTopQueries(int days, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return emptyAnswer(days);
        }
        Map<String, Object> top = rows.get(0);
        return "最近 " + days + " 天问得最多的是「" + top.get("query") + "」，共 "
                + toLong(top.get("count")) + " 次。";
    }

    private String answerUsageByDay(int days, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return emptyAnswer(days);
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            total = total.add(toDecimal(row.get("costCny")));
        }
        BigDecimal avg = total.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
        return "最近 " + days + " 天累计成本 " + fmtMoney(total) + " 元，日均 " + fmtMoney(avg) + " 元。";
    }

    private static String emptyAnswer(int days) {
        return "最近 " + days + " 天暂无数据。";
    }

    // ======================== 通用工具 ========================

    private static LocalDateTime windowStart(int days) {
        return LocalDate.now().minusDays(days - 1L).atStartOfDay();
    }

    private static LocalDate windowStartDate(int days) {
        return LocalDate.now().minusDays(days - 1L);
    }

    /** 构造列定义：参数为成对的 key/label。 */
    private static List<Map<String, Object>> columns(String... keyLabelPairs) {
        List<Map<String, Object>> columns = new ArrayList<>(keyLabelPairs.length / 2);
        for (int i = 0; i + 1 < keyLabelPairs.length; i += 2) {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("key", keyLabelPairs[i]);
            column.put("label", keyLabelPairs[i + 1]);
            columns.add(column);
        }
        return columns;
    }

    private static Map<String, Object> chart(String type, String xKey, String yKey, String seriesName) {
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", type);
        chart.put("xKey", xKey);
        chart.put("yKey", yKey);
        chart.put("seriesName", seriesName);
        return chart;
    }

    /** 取 selectMaps 结果列：兼容别名大小写差异与驼峰。 */
    private static Object cell(Map<String, Object> row, String... candidateKeys) {
        if (row == null) {
            return null;
        }
        for (String key : candidateKeys) {
            if (row.containsKey(key)) {
                return row.get(key);
            }
        }
        for (String key : candidateKeys) {
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    /** DATE/DATETIME 统一转 yyyy-MM-dd 字符串，避免 JDBC 日期类型的序列化差异。 */
    private static String toDateString(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate().toString();
        }
        if (value instanceof LocalDate localDate) {
            return localDate.toString();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.toLocalDate().toString();
        }
        String text = String.valueOf(value);
        return text.length() > 10 ? text.substring(0, 10) : text;
    }

    private static long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static Long toLongOrNull(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return 0D;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return 0D;
        }
    }

    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int clamp(Integer value, int min, int max, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static String fmtMoney(BigDecimal value) {
        BigDecimal money = value == null ? BigDecimal.ZERO : value;
        return money.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String fmtPercent(BigDecimal part, BigDecimal total) {
        if (total == null || total.compareTo(BigDecimal.ZERO) <= 0) {
            return "0";
        }
        return part.multiply(BigDecimal.valueOf(100))
                .divide(total, 0, RoundingMode.HALF_UP)
                .toPlainString();
    }

    private static String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() > maxLength ? text.substring(0, maxLength) + "…" : text;
    }

    // ======================== 内部类型 ========================

    /** LLM/关键词解析出的意图。 */
    private record Intent(String metric, Integer days, Integer limit, String dimension) {
    }

    /** 关键词兜底规则。 */
    private record KeywordRule(String metric, List<String> keywords) {
    }

    /** 单个指标的查询结果：列定义 + 数据行 + 图表配置。 */
    private record Series(List<Map<String, Object>> columns,
                          List<Map<String, Object>> rows,
                          Map<String, Object> chart) {
    }
}
