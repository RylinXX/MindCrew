package com.simon.MindCrew.service;

import com.simon.MindCrew.config.AiConfigHolder;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import com.simon.MindCrew.service.rag.VectorRetriever;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

/**
 * 语音通话专用 LLM 管线 · 任务 14 性能优化 C+D+F
 *
 * 设计要点：
 *   1. 跳过 MindCrewAgent 全 Multi-Agent 链路（planner/researcher/critic/writer）
 *   2. 仅做最轻量的 RAG：embedding top-3 chunks 塞 system prompt
 *   3. 用 ChatModel.stream() 流式输出，token 边到边回调
 *   4. 默认用 qwen-turbo（口语场景不需要 qwen-plus 的复杂推理；速度 ~× 2）
 *   5. system prompt 强制口语化输出，避免 markdown / 长段落
 *
 * 适用：语音通话每一轮对话。不替代 chat 文字场景。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceTurnService {

    private final AiConfigHolder aiConfigHolder;
    private final VectorRetriever vectorRetriever;

    /** 默认用 qwen-turbo（速度优先） · 可通过 yml 覆盖 */
    @Value("${voice.chat-model:qwen-turbo}")
    private String voiceModel;

    private static final int TOP_K = 3;
    private static final int CHUNK_MAX_LEN = 800;     // 单 chunk 最长 800 字，控制 prompt 总长

    /**
     * 流式问答 · 边出 token 边回调
     *
     * @param question      用户问题
     * @param kbIds         可访问的 KB 集（由 ACL 上游决定）
     * @param onTokenChunk  每个 token 片段回调（线程不固定，建议调用方做累积+分句）
     * @param onComplete    全部完成
     * @param onError       任意阶段失败
     */
    public void streamAnswer(String question, List<Long> kbIds,
                             Consumer<String> onTokenChunk,
                             Runnable onComplete,
                             Consumer<Throwable> onError) {
        try {
            long t0 = System.currentTimeMillis();
            String contextBlock = retrieveContext(question, kbIds);
            long tRag = System.currentTimeMillis();
            log.info("[VoiceTurn] RAG 耗时 {}ms · contextLen={}", tRag - t0, contextBlock.length());

            String system = buildSystemPrompt(contextBlock);

            OpenAiChatOptions options = OpenAiChatOptions.builder()
                    .model(voiceModel)
                    .temperature(0.4)
                    .build();

            Prompt prompt = new Prompt(
                    List.of(new SystemMessage(system), new UserMessage(question)),
                    options);

            Flux<ChatResponse> flux = aiConfigHolder.getChatModel().stream(prompt);

            flux.subscribe(
                    resp -> {
                        try {
                            String token = resp.getResult() != null
                                    && resp.getResult().getOutput() != null
                                    ? resp.getResult().getOutput().getText() : null;
                            if (token != null && !token.isEmpty()) {
                                onTokenChunk.accept(token);
                            }
                        } catch (Exception e) {
                            log.warn("[VoiceTurn] onTokenChunk 回调异常: {}", e.getMessage());
                        }
                    },
                    err -> {
                        log.error("[VoiceTurn] 流式调用失败", err);
                        if (onError != null) onError.accept(err);
                    },
                    () -> {
                        long elapsed = System.currentTimeMillis() - t0;
                        log.info("[VoiceTurn] 流完成 · total={}ms model={}", elapsed, voiceModel);
                        if (onComplete != null) onComplete.run();
                    }
            );
        } catch (Exception e) {
            log.error("[VoiceTurn] 启动失败", e);
            if (onError != null) onError.accept(e);
        }
    }

    /** 轻量 RAG · 取 top-K · 拼接为提示词上下文 */
    private String retrieveContext(String question, List<Long> kbIds) {
        try {
            List<RetrievedChunk> chunks = vectorRetriever.retrieve(question, null, kbIds, TOP_K);
            if (chunks == null || chunks.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < chunks.size(); i++) {
                RetrievedChunk c = chunks.get(i);
                String content = c.getContent();
                if (content == null || content.isBlank()) continue;
                if (content.length() > CHUNK_MAX_LEN) {
                    content = content.substring(0, CHUNK_MAX_LEN) + "...";
                }
                sb.append("【片段 ").append(i + 1).append("】\n").append(content).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("[VoiceTurn] RAG 失败，退化为无上下文: {}", e.getMessage());
            return "";
        }
    }

    private String buildSystemPrompt(String contextBlock) {
        if (contextBlock == null || contextBlock.isBlank()) {
            return """
                    你是公司的语音助手。请用自然口语回答用户的问题。

                    输出准则：
                      1. 回答必须简短（1-3 句话），适合语音播报。
                      2. 不要用 Markdown 语法（无 `#` `*` `-` 列表）。
                      3. 不要使用括号注释、英文术语括号、引号包数字。
                      4. 直接给结论；如果你不确定就直说"我没找到相关资料"。
                    """;
        }
        return """
                你是公司的语音助手。基于下面的【参考资料】回答用户问题。

                输出准则：
                  1. 答案必须简短（1-3 句话），适合语音播报。
                  2. 不要用 Markdown 语法。
                  3. 不要在答案里复述"根据资料"等冗余话术。
                  4. 如果参考资料里没有答案，直接说"我没找到相关资料"。
                  5. 答案必须基于参考资料，不要编造。

                【参考资料】
                %s
                """.formatted(contextBlock);
    }
}
