package com.simon.MindCrew.service.rag;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * RAG 链路第6步（安全兜底）：安全检查
 * 1. 检测高风险/紧急情形
 * 2. 评估检索置信度（低置信度触发兜底机制）
 * 3. 答案自评（回答是否基于检索内容）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SafetyGuard {

    private final AiConfigHolder aiConfigHolder;

    private static final Set<String> EMERGENCY_KEYWORDS = Set.of(
            "火灾", "爆炸", "触电", "泄漏", "中毒", "昏迷", "窒息", "大出血",
            "自杀", "轻生", "袭击", "生命危险", "紧急情况", "立即处理", "求救"
    );

    public boolean isEmergency(String query) {
        String lowerQuery = query.toLowerCase();
        for (String keyword : EMERGENCY_KEYWORDS) {
            if (lowerQuery.contains(keyword)) {
                log.warn("检测到高风险关键词: {}", keyword);
                return true;
            }
        }
        return false;
    }

    public float evaluateConfidence(java.util.List<RetrievedChunk> topChunks) {
        if (topChunks == null || topChunks.isEmpty()) return 0f;
        float maxScore = topChunks.stream()
                .map(c -> c.getRerankScore() > 0 ? c.getRerankScore() : c.getScore())
                .max(Float::compareTo)
                .orElse(0f);
        return Math.min(1.0f, Math.max(0.0f, maxScore));
    }

    public boolean needsFallback(java.util.List<RetrievedChunk> topChunks) {
        float threshold = aiConfigHolder.getFloat("safety.confidence_threshold");
        return evaluateConfidence(topChunks) < threshold;
    }

    public String getEmergencyWarning() {
        return "\n\n---\n" +
               "⚠️ **紧急提示**：当前问题可能涉及高风险或紧急情形。\n" +
               "**请立即联系相关专业机构、现场负责人或当地紧急服务渠道**。\n" +
               "不要仅依赖自动回答进行处置。";
    }

    public String getFallbackNotice() {
        return "\n\n---\n" +
               "ℹ️ **说明**：当前知识库中未找到与您问题高度相关的参考内容，" +
               "以上回答基于通用知识，仅供参考。\n" +
               "**建议结合更准确的资料或咨询相关专业人员**。";
    }

    public SafetyCheckResult evaluateAnswer(String answer) {
        try {
            String template = loadTemplate("safety_check");
            String prompt = template.replace("{{answer}}", answer);

            String response = aiConfigHolder.getChatModel().call(prompt).trim();
            response = response.replaceAll("```json|```", "").trim();
            JSONObject result = JSON.parseObject(response);

            return new SafetyCheckResult(
                    result.getBooleanValue("hasSafetyRisk"),
                    result.getBooleanValue("isEmergency"),
                    result.getFloatValue("confidence"),
                    result.getString("reason"),
                    result.getString("suggestion")
            );
        } catch (Exception e) {
            log.warn("答案自评失败: {}", e.getMessage());
            return new SafetyCheckResult(false, false, 0.8f, null, null);
        }
    }

    private String loadTemplate(String name) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + name + ".txt");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "{{answer}}";
        }
    }

    public record SafetyCheckResult(
            boolean hasSafetyRisk,
            boolean isEmergency,
            float confidence,
            String reason,
            String suggestion
    ) {}
}
