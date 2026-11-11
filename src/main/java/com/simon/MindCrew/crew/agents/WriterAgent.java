package com.simon.MindCrew.crew.agents;

import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.crew.dto.Finding;
import com.simon.MindCrew.service.PersonaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 撰写员 Agent。
 *
 * 把多个 Researcher 的 Finding 合成为一份带引用编号的 Markdown 报告。
 * 支持流式输出（用于前端实时渲染）和重写（用于 Critic 反馈循环）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WriterAgent {

    private final AiConfigHolder aiConfigHolder;
    private final PersonaService personaService;

    private static final String SYSTEM_PROMPT = """
            你是 MindCrew 的「撰写员」Agent，把多份调研发现合成为一份正式、严谨、可直接交付的研究报告。

            报告风格要求：
              - 学术 / 行业研究报告风格，措辞客观、克制、专业
              - **不使用任何 emoji 或表情符号**（包括标题、列表、强调等任何位置）
              - **不使用装饰性 Unicode 符号**（如 ✓ ✗ ★ ► 等），仅使用标准 Markdown
              - 标题采用纯文字命名，不带前缀图标
              - 段落以陈述句为主，避免口语化、感叹句、营销话术

            报告模板（严格遵守结构和分隔）：

            # {精炼标题，≤ 20 字}

            > **TL;DR**：{一句话核心结论，≤ 80 字}

            ---

            ## 执行摘要

            {一段 100-150 字，总结全报告的核心发现、关键数据和最终结论。}

            ---

            ## 一、{第一章节名}

            {章节正文 200-300 字。客观陈述事实，避免主观夸张。
             - 关键事实后用 [N] 标注引用，编号对应文末「参考来源」
             - 多源互证时写"多份资料印证"；存在矛盾时分别陈述
             - 关键术语用 **加粗** 强调（不要滥用）
             - 必要时使用有序或无序列表组织要点}

            **小结**：{1-2 句客观总结。}

            ---

            ## 二、{第二章节名}

            {同上结构。后续章节以"三、四、五"依次编号。}

            ---

            ## 综合结论

            {跨章节整合性结论，2-3 段：
             - 第一段：核心判断
             - 第二段：风险或不确定性
             - 第三段：建议或下一步}

            ---

            ## 参考来源

            | 编号 | 来源文档 | 章节 |
            |:----:|:--------|:-----|
            | [1]  | 《文档名》 | 章节名 |
            | [2]  | … | … |

            硬性规则：
              - 引用 [N] 必须真实对应「参考来源」表里的编号，编号必须从 [1] 起连续
              - 参考来源表中的"来源文档"必须使用 Researcher 提供的真实文档名，禁止写"未知"
              - 报告 100% 基于调研发现，资料不足时直接写"调研资料中未涉及该方面"，不要编造
              - 严禁使用 Researcher 未提及的事实、数据、人名、机构名
              - 不要重复 Researcher 的原话，要用自己的逻辑提炼、归纳、串联
            """;

    private static final String REVISION_PROMPT_SUFFIX = """

            ⚠️ 这是重写版本。请特别注意以下评审反馈：
            """;

    /**
     * 流式撰写报告。
     *
     * @param userQuery     原始问题
     * @param findings      所有 Researcher 的发现
     * @param critique      上一轮评审反馈（首次撰写传 null）
     * @param tokenConsumer 流式 token 回调（用于 SSE 推送）
     * @return 完整报告 Markdown
     */
    public String write(String userQuery,
                        List<Finding> findings,
                        String critique,
                        Consumer<String> tokenConsumer) {
        long t0 = System.currentTimeMillis();
        boolean isRevision = critique != null && !critique.isBlank();
        log.info("[WriterAgent] start writing (revision={}), {} findings", isRevision, findings.size());

        // 拼装 system prompt：先注入 Soul 人格（含反讨好底线），再加 Writer 的报告结构约束，
        // 最后追加 Critic 的重写反馈（如果是重写轮次）
        String personaPrompt = personaService.buildDefaultSystemPrompt();
        StringBuilder sb = new StringBuilder();
        if (!personaPrompt.isBlank()) {
            sb.append(personaPrompt).append("\n\n━━ 报告撰写规则 ━━\n");
        }
        sb.append(SYSTEM_PROMPT);
        if (isRevision) sb.append(REVISION_PROMPT_SUFFIX).append(critique);
        String systemPrompt = sb.toString();

        String userPrompt = """
                原始问题：%s

                Researcher 调研发现：
                %s

                请按上述报告结构撰写完整 Markdown 报告。
                """.formatted(userQuery, formatFindings(findings));

        StringBuilder fullReport = new StringBuilder();
        try {
            ChatClient client = ChatClient.builder(aiConfigHolder.getChatModel())
                    .defaultSystem(systemPrompt)
                    .build();

            Flux<String> stream = client.prompt()
                    .user(userPrompt)
                    .stream()
                    .content();

            stream.toIterable().forEach(token -> {
                if (token != null && !token.isEmpty()) {
                    fullReport.append(token);
                    if (tokenConsumer != null) {
                        try { tokenConsumer.accept(token); }
                        catch (Exception e) { log.warn("[WriterAgent] token consumer error: {}", e.getMessage()); }
                    }
                }
            });
        } catch (Exception e) {
            log.error("[WriterAgent] streaming failed, fallback to sync call: {}", e.getMessage());
            try {
                ChatClient client = ChatClient.builder(aiConfigHolder.getChatModel())
                        .defaultSystem(systemPrompt)
                        .build();
                String result = client.prompt().user(userPrompt).call().content();
                fullReport.append(result);
                if (tokenConsumer != null) tokenConsumer.accept(result);
            } catch (Exception e2) {
                log.error("[WriterAgent] sync fallback also failed: {}", e2.getMessage());
                fullReport.append("# 报告生成失败\n\n").append(e2.getMessage());
            }
        }

        log.info("[WriterAgent] done in {}ms, {} chars", System.currentTimeMillis() - t0, fullReport.length());
        return fullReport.toString();
    }

    /**
     * 给 Writer 看的 findings 文本。
     * 关键：用全局连续编号 [1]、[2]、... 让所有来源在最终报告里有唯一可引用编号。
     */
    private String formatFindings(List<Finding> findings) {
        StringBuilder sb = new StringBuilder();

        // 全局来源编号池（多个 Researcher 的源合并后去重）
        Map<String, Integer> globalRefIndex = new LinkedHashMap<>();
        StringBuilder refsTable = new StringBuilder();

        for (Finding f : findings) {
            sb.append("------------------------------------------------------------\n");
            sb.append("章节：").append(f.getSection()).append("\n");
            sb.append("子任务 #").append(f.getPlanIndex()).append("：").append(f.getTitle()).append("\n\n");
            sb.append("调研发现：\n");
            sb.append(f.getSummary() == null ? "（无内容）" : f.getSummary()).append("\n\n");

            if (f.getSources() != null && !f.getSources().isEmpty()) {
                sb.append("本章节可引用来源：\n");
                for (Finding.SourceRef ref : f.getSources()) {
                    String docName = (ref.getDocName() == null || ref.getDocName().isBlank())
                            ? "知识库文档" : ref.getDocName();
                    String chapter = (ref.getChapter() == null || ref.getChapter().isBlank())
                            ? "" : ref.getChapter();
                    // 去重 key
                    String key = docName + "|" + chapter;
                    int idx = globalRefIndex.computeIfAbsent(key, k -> globalRefIndex.size() + 1);
                    sb.append("  [").append(idx).append("] 《").append(docName).append("》");
                    if (!chapter.isEmpty()) sb.append(" — ").append(chapter);
                    sb.append("\n");
                }
                sb.append("\n");
            }
        }

        // 输出全局来源对照表（让 Writer 直接抄进报告末尾）
        refsTable.append("------------------------------------------------------------\n");
        refsTable.append("全局参考来源（请按此编号写入报告末尾的「参考来源」表）：\n");
        if (globalRefIndex.isEmpty()) {
            refsTable.append("（暂无可用来源）\n");
        } else {
            for (Map.Entry<String, Integer> e : globalRefIndex.entrySet()) {
                String[] parts = e.getKey().split("\\|", -1);
                String docName = parts.length > 0 ? parts[0] : "知识库文档";
                String chapter = parts.length > 1 ? parts[1] : "";
                refsTable.append("  [").append(e.getValue()).append("] 《").append(docName).append("》");
                if (!chapter.isEmpty()) refsTable.append(" — ").append(chapter);
                refsTable.append("\n");
            }
        }
        sb.append(refsTable);
        return sb.toString();
    }
}
