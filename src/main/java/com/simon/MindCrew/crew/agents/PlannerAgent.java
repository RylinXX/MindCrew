package com.simon.MindCrew.crew.agents;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.crew.dto.PlanItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务规划师。
 *
 * 职责：把一个复杂的用户问题，分解成 3-5 个互相独立、可并行调研的子任务，
 * 并为每个子任务指定最终报告中的章节名。
 *
 * 设计要点：
 *  - 输出严格 JSON Array（带 try-parse 兜底）
 *  - 子任务数控制在 3-5 个（过少无深度，过多浪费 token）
 *  - 章节名要适合做 Markdown 二级标题
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlannerAgent {

    private final AiConfigHolder aiConfigHolder;

    private static final String SYSTEM_PROMPT = """
            你是 MindCrew 多 Agent 系统的「任务规划师」。
            收到一个复杂问题后，你的工作是把它拆解为 3-5 个互相独立、可并行调研的子任务。

            原则：
              1. 子任务之间应低耦合，能各自检索后合并
              2. 覆盖问题的关键维度（背景/现状/对比/风险/建议 等）
              3. 子任务的 query 应足够具体，可直接送给检索 Agent 使用
              4. 每个子任务指定一个章节名（用于最终报告 Markdown）

            输出格式（严格 JSON 数组，不要额外说明）：
            [
              {
                "index": 1,
                "title": "子任务标题（≤15 字）",
                "query": "送给 Researcher 的具体检索问题",
                "section": "报告章节名（Markdown ## 标题，≤10 字）"
              }
            ]
            """;

    /** 最大重试次数 */
    private static final int MAX_RETRIES = 2;
    /** 初始退避时间（毫秒） */
    private static final long BASE_BACKOFF_MS = 1500;

    /**
     * 分解任务（带重试）。
     *
     * @param userQuery 用户原问题
     * @return 子任务列表
     */
    public List<PlanItem> plan(String userQuery) {
        log.info("[PlannerAgent] start planning, query={}", userQuery);

        ChatClient client = ChatClient.builder(aiConfigHolder.getChatModel())
                .defaultSystem(SYSTEM_PROMPT)
                .build();

        String raw = callWithRetry(client, userQuery);

        log.debug("[PlannerAgent] raw output: {}", raw);

        List<PlanItem> items = parsePlan(raw);
        if (items.isEmpty()) {
            // 兜底：解析失败时，把原问题作为单一调研任务
            log.warn("[PlannerAgent] parse failed, fallback to single-task plan");
            items.add(new PlanItem(1, "原问题调研", userQuery, "调研发现"));
        }

        log.info("[PlannerAgent] decomposed into {} sub-tasks", items.size());
        return items;
    }

    /** 带指数退避的 LLM 调用，失败时最多重试 {@value #MAX_RETRIES} 次。 */
    private String callWithRetry(ChatClient client, String userQuery) {
        Exception lastEx = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                if (attempt > 0) {
                    long backoff = BASE_BACKOFF_MS * (1L << (attempt - 1));
                    log.info("[PlannerAgent] retry attempt {}/{}, waiting {}ms", attempt, MAX_RETRIES, backoff);
                    Thread.sleep(backoff);
                }
                return client.prompt()
                        .user("用户问题：" + userQuery)
                        .call()
                        .content();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Planner interrupted during backoff", e);
            } catch (Exception e) {
                lastEx = e;
                log.warn("[PlannerAgent] LLM call failed (attempt {}): {}", attempt + 1, e.getMessage());
            }
        }
        throw new RuntimeException("Planner LLM call failed after " + (MAX_RETRIES + 1) + " attempts", lastEx);
    }

    /** 从 LLM 输出里提取 JSON 数组。支持 ```json 包裹 或 裸 JSON。 */
    private List<PlanItem> parsePlan(String raw) {
        if (raw == null || raw.isBlank()) return List.of();

        String json = extractJsonArray(raw);
        if (json == null) return List.of();

        try {
            JSONArray arr = JSON.parseArray(json);
            List<PlanItem> result = new ArrayList<>();
            for (int i = 0; i < arr.size(); i++) {
                JSONObject o = arr.getJSONObject(i);
                result.add(new PlanItem(
                        o.getIntValue("index", i + 1),
                        o.getString("title"),
                        o.getString("query"),
                        o.getString("section")
                ));
            }
            // 限制 3-5 个，过多截断
            return result.size() > 5 ? result.subList(0, 5) : result;
        } catch (Exception e) {
            log.warn("[PlannerAgent] JSON parse error: {}", e.getMessage());
            return List.of();
        }
    }

    private String extractJsonArray(String text) {
        int start = text.indexOf('[');
        int end   = text.lastIndexOf(']');
        if (start < 0 || end <= start) return null;
        return text.substring(start, end + 1);
    }
}
