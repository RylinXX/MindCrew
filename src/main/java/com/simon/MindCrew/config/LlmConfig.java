package com.simon.MindCrew.config;

import org.springframework.context.annotation.Configuration;

/**
 * LLM 模型配置
 *
 * <p>Spring AI 通过 application.yml 中的 spring.ai.openai.* 属性自动配置
 * {@code ChatModel} 和 {@code EmbeddingModel} Bean，无需在此手动声明。
 * <br>
 * 热切换能力由 {@link AiConfigHolder} 提供，在 AI 配置中心保存后
 * 会重新创建 OpenAiChatModel 并原子替换当前活跃模型引用。
 */
@Configuration
public class LlmConfig {
    // 模型 Bean 由 Spring AI Auto-Configuration 通过 application.yml 自动装配
}
