package com.simon.MindCrew.config;

import com.simon.MindCrew.entity.LlmProvider;
import com.simon.MindCrew.service.LlmProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI 运行时配置持有者
 *
 * <p>持有所有 RAG/LLM 参数的内存快照（ConcurrentHashMap），
 * 以及当前活跃的 LLM 模型实例（AtomicReference）。
 *
 * <p>配置变更时由 AiConfigService 调用 updateBatch() + refreshLlmModel()，
 * 原子替换模型引用，无需重启 Spring；正在进行的 SSE 请求持有旧引用，自然完成后丢弃。
 *
 * <p>Spring AI 的 {@code ChatModel} 同时支持同步调用（{@code call()}）
 * 和流式调用（{@code stream()}），无需分别管理两个模型引用。
 */
@Slf4j
@Component
public class AiConfigHolder {

    /** 运行时配置快照 */
    private final ConcurrentHashMap<String, String> configMap = new ConcurrentHashMap<>();

    /**
     * 当前活跃的对话模型（热替换）
     * Spring AI ChatModel 既支持同步 call() 也支持响应式 stream()，一个实例两用。
     */
    private final AtomicReference<ChatModel> activeModel;

    /** 当前活跃模型名（供用量记账 / 成本核算使用） */
    private volatile String activeChatModelName = "qwen-plus";

    /** 来自 application.yml，用于重建模型时提供连接信息 */
    @Value("${llm.base-url}")
    private String baseUrl;

    @Value("${llm.api-key}")
    private String apiKey;

    /** DB-driven Provider 服务，可空（首次启动时 PersonaService 等同时初始化）。 */
    private final LlmProviderService llmProviderService;

    /**
     * 构造器注入 Spring AI 自动配置的初始 ChatModel Bean。
     * 之后由 AiConfigInitializer 从数据库读取配置后调用 refreshLlmModel() 替换。
     *
     * @Lazy 避免循环依赖（LlmProviderService 也可能用到 ChatModel）
     */
    public AiConfigHolder(ChatModel chatModel, @Lazy LlmProviderService llmProviderService) {
        this.activeModel = new AtomicReference<>(chatModel);
        this.llmProviderService = llmProviderService;
    }

    // ======================== 读取方法 ========================

    public int getInt(String key) {
        String val = configMap.get(key);
        if (val == null) throw new IllegalStateException("AI config missing: " + key);
        return Integer.parseInt(val.trim());
    }

    public float getFloat(String key) {
        String val = configMap.get(key);
        if (val == null) throw new IllegalStateException("AI config missing: " + key);
        return Float.parseFloat(val.trim());
    }

    public String getString(String key) {
        String val = configMap.get(key);
        if (val == null) throw new IllegalStateException("AI config missing: " + key);
        return val.trim();
    }

    /** 获取当前活跃的对话模型（支持同步调用和流式调用） */
    public ChatModel getChatModel() {
        return activeModel.get();
    }

    /** 当前活跃模型名（来自 DB Provider 或 yml 默认） */
    public String getActiveChatModelName() {
        return activeChatModelName;
    }

    // ======================== 写入方法 ========================

    /** 批量更新内存快照（由 AiConfigService 在写完 DB 后调用） */
    public void updateBatch(Map<String, String> params) {
        configMap.putAll(params);
    }

    /**
     * 用当前 configMap 中的 llm.* 参数重建模型实例，原子替换。
     *
     * 数据源优先级：
     *   1. DB-driven LlmProvider（active=1 的那条）—— 跨厂商切换的核心路径
     *   2. application.yml 的 llm.base-url / llm.api-key —— 兼容老配置 / 首次启动
     */
    public void refreshLlmModel() {
        try {
            String useBaseUrl = baseUrl;
            String useApiKey  = apiKey;
            String useModel   = configMap.getOrDefault("llm.model", "qwen-plus").trim();
            double useTemp    = parseTemp(configMap.get("llm.chat_temperature"));

            // 优先从 DB-driven Provider 读取
            LlmProvider active = null;
            try { active = llmProviderService.getActive(); } catch (Exception ignored) {}
            if (active != null) {
                useBaseUrl = active.getBaseUrl();
                String dbKey = llmProviderService.decryptKey(active);
                if (dbKey != null && !dbKey.isBlank()) useApiKey = dbKey;
                if (active.getChatModel() != null && !active.getChatModel().isBlank()) {
                    useModel = active.getChatModel();
                }
                if (active.getTemperature() != null) {
                    useTemp = active.getTemperature().doubleValue();
                }
            }

            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(useBaseUrl)
                    .apiKey(useApiKey)
                    .build();
            OpenAiChatModel newModel = OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(useModel)
                            .temperature(useTemp)
                            .build())
                    .build();

            activeModel.set(newModel);
            activeChatModelName = useModel;

            log.info("[AiConfigHolder] 模型已热切换: provider={} model={} temp={} baseUrl={}",
                    active == null ? "yml-default" : active.getName(),
                    useModel, useTemp, useBaseUrl);
        } catch (Exception e) {
            log.error("[AiConfigHolder] 模型重建失败，保持原实例不变", e);
        }
    }

    private double parseTemp(String v) {
        if (v == null || v.isBlank()) return 0.7;
        try { return Double.parseDouble(v.trim()); } catch (Exception e) { return 0.7; }
    }
}
