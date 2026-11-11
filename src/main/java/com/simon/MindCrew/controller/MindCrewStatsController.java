package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.*;
import com.simon.MindCrew.mapper.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据统计大屏 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v2/stats")
@RequiredArgsConstructor
public class MindCrewStatsController {

    private final QaMessageMapper qaMessageMapper;
    private final QaConversationMapper qaConversationMapper;
    private final KbKnowledgeBaseMapper kbKnowledgeBaseMapper;
    private final McpToolRegistryMapper mcpToolRegistryMapper;
    private final SysUserMapper sysUserMapper;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    // ==================== 总览 ====================

    /**
     * 总览数据
     * GET /api/v2/stats/overview
     * 返回: total_questions, total_kb, total_users, total_conversations, today_questions
     */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        // 总提问数（role=user 的消息数）
        long totalQuestions = qaMessageMapper.selectCount(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getRole, "user"));

        // 总知识库数
        long totalKb = kbKnowledgeBaseMapper.selectCount(
                new LambdaQueryWrapper<KbKnowledgeBase>()
                        .eq(KbKnowledgeBase::getDeleted, 0));

        // 总用户数
        long totalUsers = sysUserMapper.selectCount(
                new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getDeleted, 0));

        // 总会话数
        long totalConversations = qaConversationMapper.selectCount(
                new LambdaQueryWrapper<QaConversation>()
                        .eq(QaConversation::getDeleted, 0));

        // 今日提问数
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime todayEnd = todayStart.plusDays(1);
        long todayQuestions = qaMessageMapper.selectCount(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getRole, "user")
                        .ge(QaMessage::getCreateTime, todayStart)
                        .lt(QaMessage::getCreateTime, todayEnd));

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalQuestions", totalQuestions);
        overview.put("totalKb", totalKb);
        overview.put("totalUsers", totalUsers);
        overview.put("totalConversations", totalConversations);
        overview.put("todayQuestions", todayQuestions);

        return Result.success(overview);
    }

    // ==================== 趋势 ====================

    /**
     * 近 N 天问答趋势
     * GET /api/v2/stats/trend?days=7
     * 返回: [{date, count}]
     */
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(
            @RequestParam(value = "days", defaultValue = "7") Integer days) {

        if (days == null || days < 1 || days > 90) {
            days = 7;
        }

        LocalDateTime endTime = LocalDate.now().plusDays(1).atStartOfDay();
        LocalDateTime startTime = LocalDate.now().minusDays(days - 1).atStartOfDay();

        // 查询时间范围内所有用户消息
        List<QaMessage> messages = qaMessageMapper.selectList(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getRole, "user")
                        .ge(QaMessage::getCreateTime, startTime)
                        .lt(QaMessage::getCreateTime, endTime)
                        .select(QaMessage::getCreateTime));

        // 按日期分组统计
        Map<String, Long> countByDate = messages.stream()
                .filter(m -> m.getCreateTime() != null)
                .collect(Collectors.groupingBy(
                        m -> m.getCreateTime().format(DATE_FMT),
                        Collectors.counting()));

        // 生成连续日期列表（补 0）
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            String date = LocalDate.now().minusDays(i).format(DATE_FMT);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", date);
            entry.put("count", countByDate.getOrDefault(date, 0L));
            trend.add(entry);
        }

        return Result.success(trend);
    }

    // ==================== Tool 调用分布 ====================

    /**
     * Tool 调用分布
     * GET /api/v2/stats/tool-distribution
     * 返回: [{toolName, callCount, percentage}]
     */
    @GetMapping("/tool-distribution")
    public Result<List<Map<String, Object>>> toolDistribution() {
        List<McpToolRegistry> tools = mcpToolRegistryMapper.selectList(null);

        long totalCalls = tools.stream()
                .mapToLong(t -> t.getCallCount() != null ? t.getCallCount() : 0L)
                .sum();

        List<Map<String, Object>> distribution = tools.stream()
                .map(t -> {
                    long callCount = t.getCallCount() != null ? t.getCallCount() : 0L;
                    double percentage = totalCalls > 0
                            ? Math.round(callCount * 10000.0 / totalCalls) / 100.0
                            : 0.0;
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("toolName", t.getName());
                    item.put("callCount", callCount);
                    item.put("percentage", percentage);
                    item.put("status", t.getStatus());
                    return item;
                })
                .sorted(Comparator.comparingLong(m -> -((Long) m.get("callCount"))))
                .collect(Collectors.toList());

        return Result.success(distribution);
    }

    // ==================== 热门知识库 ====================

    /**
     * 热门知识库排行（Top 10）
     * GET /api/v2/stats/hot-kb
     * 返回: [{kbId, kbName, queryCount}]
     *
     * 统计逻辑：解析 qa_message.sources JSON，提取 kbId 计数
     */
    @GetMapping("/hot-kb")
    public Result<List<Map<String, Object>>> hotKb() {
        // 查询所有 assistant 消息的 sources 字段
        List<QaMessage> assistantMessages = qaMessageMapper.selectList(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getRole, "assistant")
                        .isNotNull(QaMessage::getSources)
                        .select(QaMessage::getSources));

        // 统计各 kbId 出现次数
        Map<Long, Long> kbQueryCount = new LinkedHashMap<>();
        for (QaMessage msg : assistantMessages) {
            String sourcesJson = msg.getSources();
            if (sourcesJson == null || sourcesJson.isBlank()) continue;
            try {
                com.alibaba.fastjson2.JSONArray sources =
                        com.alibaba.fastjson2.JSON.parseArray(sourcesJson);
                if (sources == null) continue;
                for (int i = 0; i < sources.size(); i++) {
                    com.alibaba.fastjson2.JSONObject s = sources.getJSONObject(i);
                    // sources 中可能没有 kbId，只有 name；这里尝试取 kbId
                    Long kbId = s.getLong("kbId");
                    if (kbId != null) {
                        kbQueryCount.merge(kbId, 1L, Long::sum);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 如果 sources 里没有 kbId，从知识库列表直接排行
        if (kbQueryCount.isEmpty()) {
            List<KbKnowledgeBase> kbList = kbKnowledgeBaseMapper.selectList(
                    new LambdaQueryWrapper<KbKnowledgeBase>()
                            .eq(KbKnowledgeBase::getDeleted, 0)
                            .orderByDesc(KbKnowledgeBase::getCreateTime)
                            .last("LIMIT 10"));
            List<Map<String, Object>> result = kbList.stream().map(kb -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kbId", kb.getId());
                item.put("kbName", kb.getName());
                item.put("queryCount", 0);
                item.put("status", kb.getStatus());
                return item;
            }).collect(Collectors.toList());
            return Result.success(result);
        }

        // 批量查询知识库名称
        Set<Long> kbIds = kbQueryCount.keySet();
        List<KbKnowledgeBase> kbList = kbKnowledgeBaseMapper.selectList(
                new LambdaQueryWrapper<KbKnowledgeBase>()
                        .in(KbKnowledgeBase::getId, kbIds)
                        .select(KbKnowledgeBase::getId, KbKnowledgeBase::getName, KbKnowledgeBase::getStatus));
        Map<Long, KbKnowledgeBase> kbMap = kbList.stream()
                .collect(Collectors.toMap(KbKnowledgeBase::getId, kb -> kb));

        List<Map<String, Object>> hotKb = kbQueryCount.entrySet().stream()
                .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                .limit(10)
                .map(entry -> {
                    KbKnowledgeBase kb = kbMap.get(entry.getKey());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("kbId", entry.getKey());
                    item.put("kbName", kb != null ? kb.getName() : "未知");
                    item.put("queryCount", entry.getValue());
                    item.put("status", kb != null ? kb.getStatus() : null);
                    return item;
                })
                .collect(Collectors.toList());

        return Result.success(hotKb);
    }

    // ==================== 响应时间 ====================

    /**
     * 平均响应时间统计（从 qa_message.response_time 取平均值）
     * GET /api/v2/stats/response-time
     */
    @GetMapping("/response-time")
    public Result<Map<String, Object>> responseTime() {
        // 查询所有有 responseTime 的 assistant 消息
        List<QaMessage> messages = qaMessageMapper.selectList(
                new LambdaQueryWrapper<QaMessage>()
                        .eq(QaMessage::getRole, "assistant")
                        .isNotNull(QaMessage::getResponseTime)
                        .select(QaMessage::getResponseTime, QaMessage::getCreateTime));

        if (messages.isEmpty()) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("avgResponseTimeMs", 0);
            empty.put("minResponseTimeMs", 0);
            empty.put("maxResponseTimeMs", 0);
            empty.put("sampleCount", 0);
            return Result.success(empty);
        }

        IntSummaryStatistics stats = messages.stream()
                .filter(m -> m.getResponseTime() != null)
                .mapToInt(QaMessage::getResponseTime)
                .summaryStatistics();

        // 近 7 天的响应时间趋势（按天平均）
        LocalDateTime sevenDaysAgo = LocalDate.now().minusDays(6).atStartOfDay();
        Map<String, Double> avgByDay = messages.stream()
                .filter(m -> m.getCreateTime() != null
                        && m.getCreateTime().isAfter(sevenDaysAgo)
                        && m.getResponseTime() != null)
                .collect(Collectors.groupingBy(
                        m -> m.getCreateTime().format(DATE_FMT),
                        Collectors.averagingInt(QaMessage::getResponseTime)));

        // 补全近 7 天日期
        List<Map<String, Object>> dailyTrend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            String date = LocalDate.now().minusDays(i).format(DATE_FMT);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", date);
            entry.put("avgMs", Math.round(avgByDay.getOrDefault(date, 0.0)));
            dailyTrend.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("avgResponseTimeMs", Math.round(stats.getAverage()));
        result.put("minResponseTimeMs", stats.getMin());
        result.put("maxResponseTimeMs", stats.getMax());
        result.put("sampleCount", stats.getCount());
        result.put("dailyTrend", dailyTrend);

        return Result.success(result);
    }
}
