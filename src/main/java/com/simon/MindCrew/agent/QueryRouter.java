package com.simon.MindCrew.agent;

import com.simon.MindCrew.config.AiConfigHolder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 意图识别与路由
 * 先用规则快速判断，再用 LLM 辅助确认，选出本次需要调用的 Tool 列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRouter {

    private final AiConfigHolder aiConfigHolder;

    /** 意图类型常量 */
    public static final String KNOWLEDGE_QUERY = "knowledge_query";
    public static final String EXACT_SEARCH    = "exact_search";
    public static final String REALTIME        = "realtime";
    public static final String COMPOUND        = "compound";
    public static final String FOLLOWUP        = "followup";

    // 规则：时效性关键词
    private static final Pattern REALTIME_PATTERN =
            Pattern.compile("最新|2025|2026|近期|最近|今年|今天|当前|现在");

    // 规则：精确检索关键词（条文、编号、第X条等）
    private static final Pattern EXACT_PATTERN =
            Pattern.compile("第[\\d一二三四五六七八九十百]+条|第[\\d一二三四五六七八九十百]+款|" +
                    "编号|条款|规定|法条|第[\\d]+章|第[\\d]+节");

    // 规则：复合问题关键词
    private static final Pattern COMPOUND_PATTERN =
            Pattern.compile("对比|比较|区别|异同|优缺点|和.*有什么不同|与.*相比|vs\\.?");

    // 规则：追问上下文关键词
    private static final Pattern FOLLOWUP_PATTERN =
            Pattern.compile("上面|上一个|刚才|之前|前面|你说的|你刚说|继续|接着|再说|还有吗");

    /**
     * 路由主方法
     *
     * @param query 用户原始问题
     * @return IntentResult 含意图类型、工具列表、置信度
     */
    public IntentResult route(String query) {
        if (query == null || query.isBlank()) {
            return new IntentResult(KNOWLEDGE_QUERY, defaultKnowledgeTools(), 0.5);
        }

        // ---- 1. 规则快速判断 ----
        String ruleIntent = applyRules(query);
        double ruleConfidence = 0.75;

        // ---- 2. LLM 辅助识别（失败时退化到规则结果）----
        String llmIntent = ruleIntent;
        double llmConfidence = ruleConfidence;
        try {
            llmIntent = askLlm(query, ruleIntent);
            llmConfidence = 0.90;
        } catch (Exception e) {
            log.warn("[QueryRouter] LLM意图识别失败，使用规则结果: {}", e.getMessage());
        }

        // ---- 3. 综合决策（LLM 覆盖规则，但规则 REALTIME 保留）----
        String finalIntent = REALTIME.equals(ruleIntent) ? ruleIntent : llmIntent;
        double finalConfidence = REALTIME.equals(ruleIntent) ? ruleConfidence : llmConfidence;

        List<String> tools = selectTools(finalIntent);

        log.info("[QueryRouter] query='{}' → intent={} confidence={} tools={}",
                query, finalIntent, finalConfidence, tools);

        return new IntentResult(finalIntent, tools, finalConfidence);
    }

    // ==================== 私有方法 ====================

    private String applyRules(String query) {
        if (REALTIME_PATTERN.matcher(query).find()) {
            return REALTIME;
        }
        if (EXACT_PATTERN.matcher(query).find()) {
            return EXACT_SEARCH;
        }
        if (COMPOUND_PATTERN.matcher(query).find()) {
            return COMPOUND;
        }
        if (FOLLOWUP_PATTERN.matcher(query).find()) {
            return FOLLOWUP;
        }
        return KNOWLEDGE_QUERY;
    }

    /**
     * 调用 LLM 进行意图分类（仅返回意图类型字符串）
     */
    private String askLlm(String query, String ruleGuess) {
        String prompt = String.format("""
                你是一个意图分类器，请根据用户问题判断其意图类型，只返回以下五种类型之一，不要解释：
                - knowledge_query（通用知识查询）
                - exact_search（精确文本检索，如法条/条款/编号查找）
                - realtime（时效性查询，需要最新信息）
                - compound（复合问题，需要对比分析）
                - followup（追问，需要结合上下文）

                规则初判结果（供参考）：%s

                用户问题：%s

                只输出意图类型，例如：knowledge_query
                """, ruleGuess, query);

        String response = aiConfigHolder.getChatModel()
                .call(prompt)
                .trim()
                .toLowerCase();

        // 从 LLM 响应中提取合法意图
        for (String validIntent : List.of(KNOWLEDGE_QUERY, EXACT_SEARCH, REALTIME, COMPOUND, FOLLOWUP)) {
            if (response.contains(validIntent)) {
                return validIntent;
            }
        }
        // LLM 未返回合法值，回退到规则
        return ruleGuess;
    }

    /**
     * 根据意图选择工具
     */
    private List<String> selectTools(String intentType) {
        return switch (intentType) {
            case REALTIME       -> List.of("doc_search", "keyword_search", "web_search");
            case EXACT_SEARCH   -> List.of("keyword_search", "doc_search");
            case COMPOUND       -> List.of("doc_search", "keyword_search", "web_search");
            case FOLLOWUP       -> List.of("recall_memory", "doc_search");
            default             -> defaultKnowledgeTools();
        };
    }

    private List<String> defaultKnowledgeTools() {
        return List.of("doc_search", "keyword_search");
    }

    // ==================== 内部结果类 ====================

    /**
     * 路由结果
     */
    @Data
    public static class IntentResult {
        /** 意图类型 */
        private final String intentType;
        /** 选中的工具列表 */
        private final List<String> tools;
        /** 置信度 0.0-1.0 */
        private final double confidence;
    }
}
