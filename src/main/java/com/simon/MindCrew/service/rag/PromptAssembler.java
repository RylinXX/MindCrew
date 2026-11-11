package com.simon.MindCrew.service.rag;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

/**
 * RAG 链路第5步：Prompt 组装
 * 将检索结果按通用知识问答场景组织，注入角色设定、用户画像、对话历史等
 */
@Slf4j
@Component
public class PromptAssembler {

    /**
     * 组装完整 Prompt
     * @param query       用户问题（原始）
     * @param chunks      重排序后的 Top-K 切片
     * @param memoryContext 用户长期记忆
     * @param userProfile 用户补充画像（JSON字符串）
     * @param history     对话历史（格式化文本）
     */
    public String assemble(String query, List<RetrievedChunk> chunks,
                            Map<String, Object> memoryContext,
                            String userProfile, String history) {
        // 构建参考来源文本
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            contextBuilder.append("[").append(i + 1).append("] ");

            if (chunk.getSource() == RetrievedChunk.Source.WEB) {
                contextBuilder.append("网页");
                if (StringUtils.hasText(chunk.getSourceName())) {
                    contextBuilder.append("《").append(chunk.getSourceName()).append("》");
                }
                if (StringUtils.hasText(chunk.getSourceRef())) {
                    contextBuilder.append(" ").append(chunk.getSourceRef());
                }
            } else {
                contextBuilder.append("知识库");
                if (StringUtils.hasText(chunk.getSourceName())) {
                    contextBuilder.append("《").append(chunk.getSourceName()).append("》");
                }
                if (StringUtils.hasText(chunk.getChapter())) {
                    contextBuilder.append(" - ").append(chunk.getChapter());
                }
                if (chunk.getPageNumber() > 0) {
                    contextBuilder.append(" 第").append(chunk.getPageNumber()).append("页");
                }
            }
            contextBuilder.append("\n").append(chunk.getContent()).append("\n\n");
        }

        String formattedUserProfile = formatUserProfile(userProfile);
        String formattedMemory = formatMemoryContext(memoryContext);

        // 加载并填充 Prompt 模板
        String template = loadTemplate("knowledge_qa");
        String prompt = template
                .replace("{{question}}", query)
                .replace("{{context}}", contextBuilder.toString())
                .replace("{{memoryContext}}", formattedMemory)
                .replace("{{userProfile}}", formattedUserProfile)
                .replace("{{history}}", history != null ? history : "（无历史对话）");

        log.debug("Prompt组装完成: 参考来源={}条, 历史消息={}", chunks.size(),
                history != null ? "有" : "无");
        return prompt;
    }

    public String assemble(String query, List<RetrievedChunk> chunks,
                           String userProfile, String history) {
        return assemble(query, chunks, Map.of(), userProfile, history);
    }

    /**
     * 兜底 Prompt（检索置信度低时使用）
     * 保留对话历史，以支持"上一个问题是什么"等上下文引用类问题
     */
    public String assembleFallback(String query, String history) {
        String historySection = (history != null && !history.isBlank())
                ? "## 对话历史\n" + history + "\n\n"
                : "";
        return String.format("""
                你是一位专业的通用知识库问答助手。

                %s## 用户当前问题
                %s

                当前知识库中未找到与该问题高度相关的参考内容。

                请先判断用户的问题类型：
                - 若用户在引用对话历史（如"上一个问题"、"刚才"、"之前"等），请直接根据上方【对话历史】作答，不要说"无法访问历史记录"。
                - 若当前检索结果不足，请明确说明"⚠️ 当前知识库未匹配到足够相关的参考内容，以下回答基于通用常识性说明，仅供参考"。
                - 不要伪造知识库文档、网页链接或具体出处。
                """, historySection, query);
    }

    private String formatMemoryContext(Map<String, Object> memoryContext) {
        if (memoryContext == null || memoryContext.isEmpty()) {
            return "（无长期记忆）";
        }

        StringBuilder sb = new StringBuilder();
        memoryContext.forEach((key, value) -> {
            if (value != null && StringUtils.hasText(String.valueOf(value))) {
                sb.append("- ").append(key).append(": ").append(value).append("\n");
            }
        });
        return sb.isEmpty() ? "（无长期记忆）" : sb.toString();
    }

    private String formatUserProfile(String userProfileJson) {
        if (userProfileJson == null || userProfileJson.isBlank()) {
            return "（用户未提供补充画像）";
        }
        try {
            com.alibaba.fastjson2.JSONObject profile =
                    com.alibaba.fastjson2.JSON.parseObject(userProfileJson);
            StringBuilder sb = new StringBuilder();
            appendProfileLine(sb, profile, "role", "角色");
            appendProfileLine(sb, profile, "domain", "所属领域");
            appendProfileLine(sb, profile, "organization", "组织/团队");
            appendProfileLine(sb, profile, "focusTopics", "关注主题");
            appendProfileLine(sb, profile, "preferences", "表达偏好");
            appendProfileLine(sb, profile, "notes", "补充备注");

            // 兼容旧数据字段，避免历史数据在切换后直接丢失
            appendProfileLine(sb, profile, "age", "年龄");
            appendProfileLine(sb, profile, "gender", "性别");
            appendProfileLine(sb, profile, "allergies", "历史字段-allergies");
            appendProfileLine(sb, profile, "conditions", "历史字段-conditions");
            appendProfileLine(sb, profile, "medications", "历史字段-medications");
            return sb.isEmpty() ? "（用户未提供补充画像）" : sb.toString();
        } catch (Exception e) {
            return "（用户画像格式错误）";
        }
    }

    private void appendProfileLine(StringBuilder sb,
                                   com.alibaba.fastjson2.JSONObject profile,
                                   String key,
                                   String label) {
        if (!profile.containsKey(key)) {
            return;
        }
        String value = profile.getString(key);
        if (StringUtils.hasText(value)) {
            sb.append(label).append("：").append(value).append("\n");
        }
    }

    private String loadTemplate(String name) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + name + ".txt");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Prompt模板加载失败: {}", name);
            return "{{question}}";
        }
    }
}
