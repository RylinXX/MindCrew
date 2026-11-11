package com.simon.MindCrew.service.retrieval;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 检索分析 · 只读聚合服务
 *
 * 数据来源：qa_message（role='assistant'）的 retrieval_log / reflection_log / feedback /
 * latency_ms / response_time / model_name / cost_cny。
 * 首版直接内存聚合，单次最多扫描 days*500 条（契约要求）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RetrievalAnalyticsService {

    private final QaMessageMapper qaMessageMapper;
    private final KbKnowledgeBaseMapper kbKnowledgeBaseMapper;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_DAYS = 365;
    private static final int ROWS_PER_DAY = 500;

    // ============================================================
    // 3.1 总览
    // ============================================================
    public Map<String, Object> overview(int days) {
        days = normalizeDays(days);
        List<QaMessage> messages = loadMessages(days);

        long totalAnswers = messages.size();
        long withLog = 0;
        long hitCount = 0;
        long zeroResultCount = 0;
        long fallbackCount = 0;
        long rewriteCount = 0;
        long vectorSum = 0;
        long bm25Sum = 0;
        long rrfSum = 0;
        long rerankSum = 0;
        long latencySum = 0;
        long latencyCount = 0;
        long positive = 0;
        long negative = 0;
        BigDecimal totalCost = BigDecimal.ZERO;

        for (QaMessage msg : messages) {
            if (msg.getFeedback() != null) {
                if (msg.getFeedback() > 0) positive++;
                else if (msg.getFeedback() < 0) negative++;
            }
            if (msg.getCostCny() != null) totalCost = totalCost.add(msg.getCostCny());
            Integer latency = latencyOf(msg);
            if (latency != null) {
                latencySum += latency;
                latencyCount++;
            }

            JSONObject logJson = parseObject(msg.getRetrievalLog());
            if (logJson == null) continue;
            withLog++;

            int vector = logJson.getIntValue("vectorResults");
            int bm25 = logJson.getIntValue("bm25Results");
            int web = logJson.getIntValue("webResults");
            int rrf = logJson.getIntValue("rrfCount");
            int rerank = logJson.getIntValue("rerankTop");

            vectorSum += Math.max(vector, 0);
            bm25Sum += Math.max(bm25, 0);
            rrfSum += Math.max(rrf, 0);
            rerankSum += Math.max(rerank, 0);

            if (rerank > 0 || vector > 0 || bm25 > 0 || web > 0) hitCount++;
            if (vector <= 0 && bm25 <= 0 && web <= 0) zeroResultCount++;
            if (isFallback(logJson)) fallbackCount++;
            if (isRewritten(logJson)) rewriteCount++;
        }

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("days", days);
        overview.put("totalAnswers", totalAnswers);
        overview.put("withLog", withLog);
        overview.put("hitCount", hitCount);
        overview.put("hitRate", ratio(hitCount, withLog));
        overview.put("zeroResultCount", zeroResultCount);
        overview.put("zeroResultRate", ratio(zeroResultCount, withLog));
        overview.put("fallbackCount", fallbackCount);
        overview.put("fallbackRate", ratio(fallbackCount, withLog));
        overview.put("rewriteCount", rewriteCount);
        overview.put("rewriteRate", ratio(rewriteCount, withLog));
        overview.put("avgVector", avg(vectorSum, withLog));
        overview.put("avgBm25", avg(bm25Sum, withLog));
        overview.put("avgRrf", avg(rrfSum, withLog));
        overview.put("avgRerank", avg(rerankSum, withLog));
        overview.put("avgLatencyMs", latencyCount > 0 ? Math.round((double) latencySum / latencyCount) : 0L);
        overview.put("positiveFeedback", positive);
        overview.put("negativeFeedback", negative);
        overview.put("satisfactionRate", ratio(positive, positive + negative));
        overview.put("totalCostCny", totalCost.setScale(4, RoundingMode.HALF_UP).doubleValue());
        overview.put("avgCostCny", avgCost(totalCost, totalAnswers));
        return overview;
    }

    // ============================================================
    // 3.2 按天趋势（补零、日期升序）
    // ============================================================
    public List<Map<String, Object>> trend(int days) {
        days = normalizeDays(days);
        List<QaMessage> messages = loadMessages(days);

        Map<LocalDate, DayStat> byDay = new HashMap<>();
        for (QaMessage msg : messages) {
            if (msg.getCreateTime() == null) continue;
            DayStat stat = byDay.computeIfAbsent(msg.getCreateTime().toLocalDate(), d -> new DayStat());
            stat.questions++;
            JSONObject logJson = parseObject(msg.getRetrievalLog());
            if (logJson == null) continue;
            stat.withLog++;
            if (logJson.getIntValue("rerankTop") > 0
                    || logJson.getIntValue("vectorResults") > 0
                    || logJson.getIntValue("bm25Results") > 0
                    || logJson.getIntValue("webResults") > 0) {
                stat.hitCount++;
            }
            if (logJson.getIntValue("vectorResults") <= 0
                    && logJson.getIntValue("bm25Results") <= 0
                    && logJson.getIntValue("webResults") <= 0) {
                stat.zeroResultCount++;
            }
            if (isFallback(logJson)) stat.fallbackCount++;
            Integer latency = latencyOf(msg);
            if (latency != null) {
                stat.latencySum += latency;
                stat.latencyCount++;
            }
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            DayStat stat = byDay.getOrDefault(date, new DayStat());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.format(DATE_FMT));
            item.put("questions", stat.questions);
            item.put("hitRate", ratio(stat.hitCount, stat.withLog));
            item.put("zeroResultCount", stat.zeroResultCount);
            item.put("avgLatencyMs", stat.latencyCount > 0
                    ? Math.round((double) stat.latencySum / stat.latencyCount) : 0L);
            item.put("fallbackCount", stat.fallbackCount);
            trend.add(item);
        }
        return trend;
    }

    // ============================================================
    // 3.3 工具分布（selectedTools 为空记入 none）
    // ============================================================
    public List<Map<String, Object>> tools(int days) {
        days = normalizeDays(days);
        List<QaMessage> messages = loadMessages(days);

        Map<String, Long> counts = new LinkedHashMap<>();
        long totalSelections = 0;
        for (QaMessage msg : messages) {
            JSONObject logJson = parseObject(msg.getRetrievalLog());
            if (logJson == null) continue;
            JSONArray toolsJson = logJson.getJSONArray("selectedTools");
            if (toolsJson == null || toolsJson.isEmpty()) {
                counts.merge("none", 1L, Long::sum);
                totalSelections++;
                continue;
            }
            for (int i = 0; i < toolsJson.size(); i++) {
                String tool = toolsJson.getString(i);
                if (tool == null || tool.isBlank()) tool = "none";
                counts.merge(tool, 1L, Long::sum);
                totalSelections++;
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", entry.getKey());
            item.put("count", entry.getValue());
            item.put("ratio", ratio(entry.getValue(), totalSelections));
            result.add(item);
        }
        result.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
        return result;
    }

    // ============================================================
    // 3.4 知识库维度（selectedKbIds 多值每个都计数）
    // ============================================================
    public List<Map<String, Object>> kbs(int days) {
        days = normalizeDays(days);
        List<QaMessage> messages = loadMessages(days);

        Map<Long, KbStat> stats = new LinkedHashMap<>();
        for (QaMessage msg : messages) {
            JSONObject logJson = parseObject(msg.getRetrievalLog());
            if (logJson == null) continue;
            JSONArray kbIds = logJson.getJSONArray("selectedKbIds");
            if (kbIds == null || kbIds.isEmpty()) continue;
            boolean hit = logJson.getIntValue("rerankTop") > 0
                    || logJson.getIntValue("vectorResults") > 0
                    || logJson.getIntValue("bm25Results") > 0
                    || logJson.getIntValue("webResults") > 0;
            int rerank = Math.max(logJson.getIntValue("rerankTop"), 0);
            for (int i = 0; i < kbIds.size(); i++) {
                Long kbId = kbIds.getLong(i);
                if (kbId == null) continue;
                KbStat stat = stats.computeIfAbsent(kbId, id -> new KbStat());
                stat.count++;
                if (hit) stat.hitCount++;
                stat.rerankSum += rerank;
            }
        }

        Map<Long, String> nameById = kbNames(stats.keySet());
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<Long, KbStat> entry : stats.entrySet()) {
            Long kbId = entry.getKey();
            KbStat stat = entry.getValue();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kbId", kbId);
            item.put("kbName", nameById.getOrDefault(kbId, "#" + kbId));
            item.put("count", stat.count);
            item.put("hitRate", ratio(stat.hitCount, stat.count));
            item.put("avgRerank", avg(stat.rerankSum, stat.count));
            result.add(item);
        }
        result.sort((a, b) -> Long.compare((Long) b.get("count"), (Long) a.get("count")));
        return result;
    }

    // ============================================================
    // 3.5 低质量查询分页
    // ============================================================
    public Map<String, Object> lowQuality(int days, String type, int current, int size) {
        days = normalizeDays(days);
        String normalizedType = normalizeType(type);
        int safeCurrent = Math.max(current, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);

        List<QaMessage> messages = loadMessages(days);
        List<QaMessage> filtered = new ArrayList<>();
        for (QaMessage msg : messages) {
            if (matchesType(msg, normalizedType)) filtered.add(msg);
        }

        int total = filtered.size();
        int from = Math.min((safeCurrent - 1) * safeSize, total);
        int to = Math.min(from + safeSize, total);
        List<QaMessage> pageRows = filtered.subList(from, to);
        Map<Long, String> questions = questionMap(pageRows);

        List<Map<String, Object>> records = new ArrayList<>();
        for (QaMessage msg : pageRows) {
            records.add(toLowQualityRecord(msg, questions.get(msg.getId())));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", records);
        result.put("total", total);
        result.put("current", safeCurrent);
        result.put("size", safeSize);
        return result;
    }

    // ============================================================
    // 3.6 单条明细
    // ============================================================
    public Map<String, Object> detail(Long messageId) {
        if (messageId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "messageId 不能为空");
        }
        QaMessage message = qaMessageMapper.selectById(messageId);
        if (message == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "消息不存在");
        }
        if (!"assistant".equals(message.getRole())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "仅支持 assistant 消息");
        }

        String question = questionMap(List.of(message)).get(message.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("messageId", message.getId());
        result.put("question", question);
        result.put("answer", message.getContent());
        result.put("retrievalLog", parseLenient(message.getRetrievalLog()));
        result.put("reflectionLog", parseLenient(message.getReflectionLog()));
        result.put("agentTrace", parseLenient(message.getAgentTrace()));
        result.put("sources", parseLenient(message.getSources()));
        result.put("feedback", message.getFeedback() == null ? 0 : message.getFeedback());
        result.put("latencyMs", latencyOf(message) == null ? 0 : latencyOf(message));
        return result;
    }

    // ============================================================
    // 3.7 CSV 导出（UTF-8 BOM，中文表头）
    // ============================================================
    public byte[] exportLowQualityCsv(int days, String type) {
        days = normalizeDays(days);
        String normalizedType = normalizeType(type);
        List<QaMessage> messages = loadMessages(days);

        List<QaMessage> filtered = new ArrayList<>();
        for (QaMessage msg : messages) {
            if (matchesType(msg, normalizedType)) filtered.add(msg);
        }
        int maxRows = Math.min(filtered.size(), 5000);
        filtered = filtered.subList(0, maxRows);
        Map<Long, String> questions = questionMap(filtered);

        StringBuilder sb = new StringBuilder();
        sb.append("问题,改写后,向量召回,BM25,RRF,重排,兜底,反馈,时延ms,时间\n");
        for (QaMessage msg : filtered) {
            JSONObject logJson = parseObject(msg.getRetrievalLog());
            sb.append(csvEscape(questions.get(msg.getId()))).append(',')
              .append(csvEscape(logJson == null ? "" : logJson.getString("rewrittenQuery"))).append(',')
              .append(logJson == null ? 0 : logJson.getIntValue("vectorResults")).append(',')
              .append(logJson == null ? 0 : logJson.getIntValue("bm25Results")).append(',')
              .append(logJson == null ? 0 : logJson.getIntValue("rrfCount")).append(',')
              .append(logJson == null ? 0 : logJson.getIntValue("rerankTop")).append(',')
              .append(logJson != null && isFallback(logJson)).append(',')
              .append(msg.getFeedback() == null ? 0 : msg.getFeedback()).append(',')
              .append(latencyOf(msg) == null ? 0 : latencyOf(msg)).append(',')
              .append(msg.getCreateTime() == null ? "" : msg.getCreateTime().format(DT_FMT))
              .append('\n');
        }
        return ("\uFEFF" + sb).getBytes(StandardCharsets.UTF_8);
    }

    // ============================================================
    // 内部工具
    // ============================================================

    /** 当前时间范围内 assistant 消息，最多 days*500 条，按时间倒序 */
    private List<QaMessage> loadMessages(int days) {
        LocalDateTime end = LocalDate.now().plusDays(1).atStartOfDay();
        LocalDateTime start = LocalDate.now().minusDays(days - 1L).atStartOfDay();
        int limit = days * ROWS_PER_DAY;
        List<QaMessage> messages = qaMessageMapper.selectList(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getRole, "assistant")
                        .ge(QaMessage::getCreateTime, start)
                        .lt(QaMessage::getCreateTime, end)
                        .orderByDesc(QaMessage::getCreateTime)
                        .last("LIMIT " + limit));
        if (messages != null && messages.size() >= limit) {
            log.warn("[RetrievalAnalytics] 扫描达到上限 {} 条（days={}），结果已截断", limit, days);
        }
        return messages == null ? List.of() : messages;
    }

    /** 批量补全低质量列表的原始问题（同会话中时间不晚于 assistant 的最后一条 user 消息） */
    private Map<Long, String> questionMap(List<QaMessage> answers) {
        Map<Long, String> result = new HashMap<>();
        if (answers.isEmpty()) return result;

        List<Long> conversationIds = answers.stream()
                .map(QaMessage::getConversationId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (conversationIds.isEmpty()) return result;

        Map<Long, List<QaMessage>> userMessagesByConv = new HashMap<>();
        int batch = 500;
        for (int i = 0; i < conversationIds.size(); i += batch) {
            List<Long> chunk = conversationIds.subList(i, Math.min(i + batch, conversationIds.size()));
            List<QaMessage> users = qaMessageMapper.selectList(
                    new LambdaQueryWrapper<QaMessage>()
                            .eq(QaMessage::getRole, "user")
                            .in(QaMessage::getConversationId, chunk)
                            .select(QaMessage::getId, QaMessage::getConversationId,
                                    QaMessage::getContent, QaMessage::getCreateTime)
                            .orderByAsc(QaMessage::getCreateTime));
            if (users == null) continue;
            for (QaMessage user : users) {
                userMessagesByConv.computeIfAbsent(user.getConversationId(), k -> new ArrayList<>()).add(user);
            }
        }

        for (QaMessage answer : answers) {
            List<QaMessage> users = userMessagesByConv.get(answer.getConversationId());
            if (users == null || users.isEmpty()) continue;
            LocalDateTime answerTime = answer.getCreateTime();
            for (int i = users.size() - 1; i >= 0; i--) {
                QaMessage user = users.get(i);
                if (answerTime == null || user.getCreateTime() == null
                        || !user.getCreateTime().isAfter(answerTime)) {
                    result.put(answer.getId(), user.getContent());
                    break;
                }
            }
        }
        return result;
    }

    private Map<Long, String> kbNames(Set<Long> kbIds) {
        Map<Long, String> names = new HashMap<>();
        if (kbIds == null || kbIds.isEmpty()) return names;
        List<KbKnowledgeBase> kbs = kbKnowledgeBaseMapper.selectBatchIds(kbIds);
        if (kbs == null) return names;
        for (KbKnowledgeBase kb : kbs) {
            if (kb.getId() != null) names.put(kb.getId(), kb.getName());
        }
        return names;
    }

    private Map<String, Object> toLowQualityRecord(QaMessage msg, String question) {
        JSONObject logJson = parseObject(msg.getRetrievalLog());
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("messageId", msg.getId());
        record.put("conversationId", msg.getConversationId());
        record.put("question", question);
        record.put("rewrittenQuery", logJson == null ? null : logJson.getString("rewrittenQuery"));
        record.put("vectorResults", logJson == null ? 0 : logJson.getIntValue("vectorResults"));
        record.put("bm25Results", logJson == null ? 0 : logJson.getIntValue("bm25Results"));
        record.put("rrfCount", logJson == null ? 0 : logJson.getIntValue("rrfCount"));
        record.put("rerankTop", logJson == null ? 0 : logJson.getIntValue("rerankTop"));
        record.put("fallback", logJson != null && isFallback(logJson));
        record.put("feedback", msg.getFeedback() == null ? 0 : msg.getFeedback());
        record.put("latencyMs", latencyOf(msg) == null ? 0 : latencyOf(msg));
        record.put("modelName", msg.getModelName());
        record.put("createTime", msg.getCreateTime() == null ? null : msg.getCreateTime().format(DT_FMT));
        return record;
    }

    private boolean matchesType(QaMessage msg, String type) {
        JSONObject logJson = parseObject(msg.getRetrievalLog());
        if ("negative".equals(type)) {
            return msg.getFeedback() != null && msg.getFeedback() < 0;
        }
        if (logJson == null) return false;
        if ("fallback".equals(type)) return isFallback(logJson);
        return logJson.getIntValue("vectorResults") <= 0
                && logJson.getIntValue("bm25Results") <= 0
                && logJson.getIntValue("webResults") <= 0;
    }

    private boolean isFallback(JSONObject logJson) {
        return logJson.getBooleanValue("isFallback") || logJson.getIntValue("rerankTop") <= 0;
    }

    private boolean isRewritten(JSONObject logJson) {
        String original = logJson.getString("originalQuery");
        String rewritten = logJson.getString("rewrittenQuery");
        return rewritten != null && !rewritten.isBlank()
                && (original == null || !rewritten.equals(original));
    }

    private Integer latencyOf(QaMessage msg) {
        if (msg.getLatencyMs() != null) return msg.getLatencyMs();
        return msg.getResponseTime();
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return "zero";
        String normalized = type.trim().toLowerCase();
        if ("fallback".equals(normalized) || "negative".equals(normalized)) return normalized;
        if (!"zero".equals(normalized)) {
            log.warn("[RetrievalAnalytics] 未知的 low-quality type={}，按 zero 处理", type);
        }
        return "zero";
    }

    private int normalizeDays(int days) {
        if (days < 1) return 1;
        return Math.min(days, MAX_DAYS);
    }

    /** 解析 JSON 对象；空/非法返回 null，绝不向上抛异常 */
    private JSONObject parseObject(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JSONObject object = JSON.parseObject(json);
            return object == null ? null : object;
        } catch (Exception e) {
            return null;
        }
    }

    /** 明细接口需要原样返回数组/对象；解析失败时退化为原始字符串 */
    private Object parseLenient(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.parse(json);
        } catch (Exception e) {
            return json;
        }
    }

    private static double ratio(long numerator, long denominator) {
        if (denominator <= 0) return 0.0;
        return round4((double) numerator / denominator);
    }

    private static double avg(long sum, long count) {
        if (count <= 0) return 0.0;
        return round2((double) sum / count);
    }

    private static double avgCost(BigDecimal total, long count) {
        if (count <= 0 || total == null) return 0.0;
        return total.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round4(double value) {
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        String cleaned = value.replace("\r", " ").replace("\n", " ");
        // 防 CSV 公式注入：以 = + - @ 或制表符开头的单元格加单引号前缀（Excel/WPS 会按文本处理）
        if (!cleaned.isEmpty()) {
            char first = cleaned.charAt(0);
            if (first == '=' || first == '+' || first == '-' || first == '@' || first == '\t') {
                cleaned = "'" + cleaned;
            }
        }
        if (cleaned.contains(",") || cleaned.contains("\"")) {
            return "\"" + cleaned.replace("\"", "\"\"") + "\"";
        }
        return cleaned;
    }

    /** 按天聚合的中间态 */
    private static final class DayStat {
        long questions;
        long withLog;
        long hitCount;
        long zeroResultCount;
        long fallbackCount;
        long latencySum;
        long latencyCount;
    }

    /** 按 KB 聚合的中间态 */
    private static final class KbStat {
        long count;
        long hitCount;
        long rerankSum;
    }
}
