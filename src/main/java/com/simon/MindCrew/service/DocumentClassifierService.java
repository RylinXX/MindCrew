package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.entity.KbCategory;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 文档自动分类服务。
 *
 * 一次 LLM 调用同时产出：
 *   - category (code)
 *   - tags     (3-7 个标签)
 *   - summary  (100-200 字摘要)
 *
 * 策略：
 *   1. 取文档前 N 字符（约 2000 token）作为分类样本，避免长文档把上下文撑爆。
 *   2. 强制 LLM 在已知 code 列表里选；未匹配走 "other"。
 *   3. category_user_set=1 的文档跳过（用户手动设过的尊重用户）。
 *   4. 失败不抛异常，记 warn，保留原 category（通常 yml/上传时填的默认值）。
 *
 * 调用时机：DocumentProcessTask 在 chunks 全部入库、状态变 ready 之前。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentClassifierService {

    private final AiConfigHolder aiConfigHolder;
    private final KbCategoryService categoryService;
    private final KbKnowledgeBaseMapper kbMapper;

    /** 喂给 LLM 的样本最大字符数（中文约 1.5 char/token，2400 char ≈ 1600 token） */
    private static final int SAMPLE_MAX_CHARS = 2400;

    /**
     * 对单个文档执行分类。失败不抛异常，只 warn 并保留原状态。
     *
     * @param kbId             被分类的文档 ID
     * @param fileName         文件名（喂 prompt 用）
     * @param categoryUserSet  1 表示用户手动锁定（跳过）；其他正常分类
     * @param fullText         文档全文（视频/音频用 transcript 拼出来的文本）
     */
    public void classify(Long kbId, String fileName, Integer categoryUserSet, String fullText) {
        if (Integer.valueOf(1).equals(categoryUserSet)) {
            log.debug("[Classifier] 跳过 · 用户手动锁定: id={}", kbId);
            return;
        }
        if (kbId == null || fullText == null || fullText.isBlank()) {
            return;
        }
        try {
            ClassifyOutcome r = doClassify(fileName, fullText);
            if (r == null) {
                log.warn("[Classifier] LLM 输出为空，保留原分类: id={}", kbId);
                return;
            }

            KbKnowledgeBase patch = new KbKnowledgeBase();
            patch.setId(kbId);
            if (r.category != null && !r.category.isBlank()) patch.setCategory(r.category);
            if (r.summary  != null && !r.summary.isBlank())  patch.setSummary(r.summary);
            if (r.tags     != null && !r.tags.isEmpty())     patch.setTags(JSON.toJSONString(r.tags));
            kbMapper.updateById(patch);

            log.info("[Classifier] 文档分类完成 · id={} name={} → category={} tags={}",
                    kbId, fileName, r.category, r.tags);
        } catch (Exception e) {
            log.warn("[Classifier] 失败，保留原分类: id={} err={}", kbId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 内部实现
    // ─────────────────────────────────────────────
    private ClassifyOutcome doClassify(String fileName, String fullText) {
        List<KbCategory> categories = categoryService.list();
        if (categories.isEmpty()) {
            log.warn("[Classifier] 字典为空，无法分类（请执行 sql/kb-category-schema.sql）");
            return null;
        }

        String sample = sample(fullText);
        String prompt = buildPrompt(categories, fileName, sample);

        ChatResponse resp = aiConfigHolder.getChatModel().call(new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(prompt)
        )));
        String raw = resp.getResult().getOutput().getText();
        return parseJson(raw, categoryService.codes());
    }

    private static final String SYSTEM_PROMPT = """
            你是企业知识库的文档分类助手。请严格按用户指定的 JSON 格式输出，不要任何额外文字、注释、Markdown 包裹。
            只输出 JSON 对象，键必须是 category / tags / summary。
            """;

    private String buildPrompt(List<KbCategory> cats, String fileName, String sample) {
        StringBuilder sb = new StringBuilder();
        sb.append("【任务】根据下面的文档样本，输出 JSON 三件事：\n");
        sb.append("  1. category：从下方候选 code 中选一个最匹配的（必须严格匹配 code）。\n");
        sb.append("  2. tags：3 到 7 个最能代表文档主题的关键词（中文），每个 2-6 字。\n");
        sb.append("  3. summary：100-200 字的中文摘要，必须基于文档实际内容，不要编造。\n");
        sb.append("\n【候选 category code】（必须从中选一个）\n");
        for (KbCategory c : cats) {
            sb.append("  - ").append(c.getCode())
              .append(" (").append(c.getName()).append(") ")
              .append(c.getDescription() == null ? "" : c.getDescription())
              .append('\n');
        }
        sb.append("\n【文档文件名】").append(fileName == null ? "" : fileName).append('\n');
        sb.append("\n【文档样本（节选）】\n").append(sample).append('\n');
        sb.append("\n【输出格式（严格 JSON，不要 ```包裹）】\n");
        sb.append("{\"category\":\"<code>\",\"tags\":[\"...\",\"...\"],\"summary\":\"...\"}\n");
        return sb.toString();
    }

    private String sample(String text) {
        if (text.length() <= SAMPLE_MAX_CHARS) return text;
        // 取前 70% + 末尾 30% 一起，避免只看开头错过结论段
        int head = (int) (SAMPLE_MAX_CHARS * 0.7);
        int tail = SAMPLE_MAX_CHARS - head;
        return text.substring(0, head) + "\n... [中间内容省略] ...\n" + text.substring(text.length() - tail);
    }

    /** 容错解析：去掉可能的 markdown 代码块包裹 */
    private ClassifyOutcome parseJson(String raw, List<String> allowedCodes) {
        if (raw == null || raw.isBlank()) return null;
        String cleaned = raw.trim();
        // 去掉 ```json 包裹
        if (cleaned.startsWith("```")) {
            int firstBrace = cleaned.indexOf('{');
            int lastBrace  = cleaned.lastIndexOf('}');
            if (firstBrace >= 0 && lastBrace > firstBrace) {
                cleaned = cleaned.substring(firstBrace, lastBrace + 1);
            }
        }
        JSONObject obj;
        try {
            obj = JSON.parseObject(cleaned);
        } catch (Exception e) {
            log.warn("[Classifier] JSON 解析失败 raw={}", raw);
            return null;
        }
        ClassifyOutcome r = new ClassifyOutcome();
        String cat = obj.getString("category");
        // 标准化 + 兜底
        if (cat != null) cat = cat.trim().toLowerCase(Locale.ROOT);
        r.category = allowedCodes.contains(cat) ? cat : "other";

        JSONArray arr = obj.getJSONArray("tags");
        r.tags = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.size() && r.tags.size() < 10; i++) {
                String t = arr.getString(i);
                if (t != null && !t.isBlank()) r.tags.add(t.trim());
            }
        }
        r.summary = obj.getString("summary");
        if (r.summary != null) r.summary = r.summary.trim();
        return r;
    }

    private static class ClassifyOutcome {
        String category;
        List<String> tags;
        String summary;
    }
}
