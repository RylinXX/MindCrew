package com.simon.MindCrew.service.rag;

import com.simon.MindCrew.config.AiConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * RAG 链路第1步：Query 改写
 * 将用户口语化问题改写为更适合检索的标准查询，提升召回率
 * 示例："这个规范怎么配" → "该规范的配置步骤与参数说明"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryRewriter {

    private final AiConfigHolder aiConfigHolder;

    private static final int MAX_QUERY_LENGTH = 200;

    /**
     * 改写用户查询
     * @param originalQuery 原始用户输入
     * @return 改写后的标准查询词
     */
    public String rewrite(String originalQuery) {
        if (originalQuery.length() <= 10) {
            log.debug("查询过短，跳过改写: {}", originalQuery);
            return originalQuery;
        }

        try {
            String prompt = loadPromptTemplate("query_rewrite")
                    .replace("{{question}}", originalQuery);

            String rewritten = aiConfigHolder.getChatModel().call(prompt).trim();

            // 改写结果异常保护：空串 / 超长 / 多行（模型跑偏时）一律回退原始查询，
            // 避免把一段回答当成检索词，污染召回。
            int maxAllowed = Math.max(60, originalQuery.length() * 3);
            if (rewritten.isBlank() || rewritten.length() > maxAllowed || rewritten.contains("\n")) {
                log.warn("Query改写结果异常（长度={}），使用原始查询: {}", rewritten.length(), rewritten);
                return originalQuery;
            }
            if (rewritten.length() > MAX_QUERY_LENGTH) {
                rewritten = rewritten.substring(0, MAX_QUERY_LENGTH);
            }

            log.info("Query改写: [{}] → [{}]", originalQuery, rewritten);
            return rewritten;

        } catch (Exception e) {
            log.warn("Query改写失败，使用原始查询: {}", e.getMessage());
            return originalQuery;
        }
    }

    private String loadPromptTemplate(String name) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + name + ".txt");
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Prompt模板加载失败: {}", name);
            return "{{question}}";
        }
    }
}
