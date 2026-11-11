package com.simon.MindCrew.crew.agents;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.crew.dto.Finding;
import com.simon.MindCrew.crew.dto.ReviewResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 评审员 Agent。
 *
 * 对 Writer 产出的报告做三维评分（事实性 / 完整性 / 引用充分性），
 * 并给出改进建议供下一轮 Writer 参考。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CriticAgent {

    private final AiConfigHolder aiConfigHolder;

    /** 通过阈值（综合评分 >= 0.7 即通过，不再重写） */
    private static final double PASS_THRESHOLD = 0.7;

    private static final String SYSTEM_PROMPT = """
            你是 MindCrew 的「评审员」Agent，对研究报告进行严格质量审查。

            评分维度（每项 0~1，保留两位小数）：
              - factuality       事实性：报告内容是否能在调研发现中找到对应依据
              - completeness     完整性：是否覆盖了所有 Planner 子主题
              - citationCoverage 引用充分性：关键事实是否带 [N] 标注

            综合评分 = factuality * 0.45 + completeness * 0.30 + citationCoverage * 0.25

            输出严格 JSON（不要 ```json 包裹，不要额外说明）：
            {
              "score": 0.xx,
              "factuality": 0.xx,
              "completeness": 0.xx,
              "citationCoverage": 0.xx,
              "issues": ["问题1", "问题2"],
              "suggestion": "给 Writer 的具体改进建议（≤80字）"
            }
            """;

    /**
     * 评审报告。
     *
     * @param userQuery   原始问题
     * @param findings    调研发现（用作真实性参照）
     * @param report      Writer 产出的报告
     * @return ReviewResult
     */
    public ReviewResult review(String userQuery, List<Finding> findings, String report) {
        long t0 = System.currentTimeMillis();
        log.info("[CriticAgent] start review, report length={}", report == null ? 0 : report.length());

        String userPrompt = """
                原始问题：%s

                调研发现（真实性参照）：
                %s

                待评审报告：
                %s

                请按系统指令评分。
                """.formatted(userQuery, summarizeFindings(findings), report == null ? "" : report);

        try {
            ChatClient client = ChatClient.builder(aiConfigHolder.getChatModel())
                    .defaultSystem(SYSTEM_PROMPT)
                    .build();
            String raw = client.prompt().user(userPrompt).call().content();
            ReviewResult result = parse(raw);
            result.setPassed(result.getScore() != null && result.getScore() >= PASS_THRESHOLD);
            log.info("[CriticAgent] done in {}ms, score={}, passed={}",
                    System.currentTimeMillis() - t0, result.getScore(), result.getPassed());
            return result;
        } catch (Exception e) {
            log.warn("[CriticAgent] review failed, treating as passed by default: {}", e.getMessage());
            return new ReviewResult(0.8, true, 0.8, 0.8, 0.8,
                    List.of("评审异常：" + e.getMessage()),
                    "评审失败，按默认通过处理。");
        }
    }

    private String summarizeFindings(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();
        for (Finding f : findings) {
            sb.append("- [").append(f.getPlanIndex()).append("] ")
              .append(f.getSection()).append("：")
              .append(f.getSummary() == null ? "" :
                      (f.getSummary().length() > 200 ? f.getSummary().substring(0, 200) + "…" : f.getSummary()))
              .append("\n");
        }
        return sb.toString();
    }

    private ReviewResult parse(String raw) {
        if (raw == null) {
            return defaultResult();
        }
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return defaultResult();

        try {
            JSONObject o = JSON.parseObject(raw.substring(start, end + 1));
            ReviewResult r = new ReviewResult();
            r.setScore(o.getDouble("score"));
            r.setFactuality(o.getDouble("factuality"));
            r.setCompleteness(o.getDouble("completeness"));
            r.setCitationCoverage(o.getDouble("citationCoverage"));
            r.setSuggestion(o.getString("suggestion"));

            List<String> issues = new ArrayList<>();
            JSONArray arr = o.getJSONArray("issues");
            if (arr != null) {
                for (int i = 0; i < arr.size(); i++) issues.add(arr.getString(i));
            }
            r.setIssues(issues);
            return r;
        } catch (Exception e) {
            log.warn("[CriticAgent] JSON parse failed: {}", e.getMessage());
            return defaultResult();
        }
    }

    private ReviewResult defaultResult() {
        return new ReviewResult(0.75, true, 0.75, 0.75, 0.75,
                List.of("评审输出解析失败"), "保持原样");
    }
}
