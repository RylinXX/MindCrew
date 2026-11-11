package com.simon.MindCrew.config;

import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.controller.SpeechWebSocketServer;
import com.simon.MindCrew.controller.VoiceCallWebSocketServer;
import com.simon.MindCrew.common.utils.JwtUtils;
import com.simon.MindCrew.service.CosyVoiceTtsService;
import com.simon.MindCrew.service.VoicePersonaService;
import com.simon.MindCrew.service.VoiceTurnService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.server.standard.ServerEndpointExporter;

import java.util.concurrent.TimeUnit;

/**
 * WebSocket 配置 - 启用 JSR-356 @ServerEndpoint 支持，并注入静态依赖
 */
@Configuration
@RequiredArgsConstructor
public class WebSocketConfig {

    private final JwtUtils jwtUtils;
    private final MindCrewAgent mindCrewAgent;
    private final CosyVoiceTtsService cosyVoiceTtsService;
    private final VoicePersonaService voicePersonaService;
    private final VoiceTurnService voiceTurnService;

    @Value("${llm.api-key}")
    private String apiKey;

    @Bean
    public ServerEndpointExporter serverEndpointExporter() {
        return new ServerEndpointExporter();
    }

    /**
     * 将 Spring Bean 注入到 @ServerEndpoint（容器创建，非 Spring 管理，用静态字段）
     */
    @PostConstruct
    public void initWebSocketDependencies() {
        OkHttpClient shared = new OkHttpClient.Builder()
                .readTimeout(10, TimeUnit.MINUTES)
                .connectTimeout(15, TimeUnit.SECONDS)
                .build();

        // 任务 14.3 现有 ASR
        SpeechWebSocketServer.jwtUtils = jwtUtils;
        SpeechWebSocketServer.apiKey = apiKey;
        SpeechWebSocketServer.okHttpClient = shared;

        // 任务 14.5 通话整合
        VoiceCallWebSocketServer.jwtUtils = jwtUtils;
        VoiceCallWebSocketServer.apiKey = apiKey;
        VoiceCallWebSocketServer.okHttpClient = shared;
        VoiceCallWebSocketServer.mindCrewAgent = mindCrewAgent;
        VoiceCallWebSocketServer.ttsService = cosyVoiceTtsService;
        VoiceCallWebSocketServer.voicePersonaService = voicePersonaService;
        VoiceCallWebSocketServer.voiceTurnService = voiceTurnService;
    }
}
