package com.simon.MindCrew.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.common.utils.JwtUtils;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * 语音识别 WebSocket 服务端
 * 接收前端 PCM 音频流，代理到阿里云 DashScope Paraformer 实时 ASR，将识别结果推回前端
 */
@Slf4j
@Component
@ServerEndpoint("/api/speech/ws")
public class SpeechWebSocketServer {

    // ---- 静态依赖（由 WebSocketConfig @PostConstruct 注入）----
    public static JwtUtils jwtUtils;
    public static String apiKey;
    public static OkHttpClient okHttpClient;

    private static final String DASHSCOPE_ASR_URL =
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";

    // ---- 每个连接的实例状态 ----
    private Session clientSession;
    private WebSocket dashscopeWs;
    private String taskId;
    private volatile boolean taskStarted = false;
    /** 已确认的完整文本（各句最终结果拼接） */
    private final StringBuilder confirmedText = new StringBuilder();

    // =========================================================
    // WebSocket 生命周期
    // =========================================================

    @OnOpen
    public void onOpen(Session session) {
        this.clientSession = session;

        // 验证 JWT
        String token = extractParam(session.getQueryString(), "token");
        if (token == null || !jwtUtils.validateToken(token)) {
            closeWithError(session, "无效的 Token，请重新登录");
            return;
        }

        log.info("[语音ASR] 客户端连接: {}", session.getId());
        connectToDashScope();
    }

    @OnMessage
    public void onTextMessage(String message, Session session) {
        switch (message) {
            case "START" -> sendRunTask();
            case "STOP"  -> sendFinishTask();
            default      -> log.warn("[语音ASR] 未知文本消息: {}", message);
        }
    }

    @OnMessage
    public void onBinaryMessage(ByteBuffer buffer, Session session) {
        if (!taskStarted || dashscopeWs == null) return;
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        dashscopeWs.send(ByteString.of(bytes));
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        log.info("[语音ASR] 连接关闭: {} reason={}", session.getId(), reason.getReasonPhrase());
        cleanup();
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("[语音ASR] WebSocket 错误: {}", error.getMessage());
        cleanup();
    }

    // =========================================================
    // DashScope 连接
    // =========================================================

    private void connectToDashScope() {
        Request request = new Request.Builder()
                .url(DASHSCOPE_ASR_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-DataInspection", "enable")
                .build();

        dashscopeWs = okHttpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.info("[语音ASR] 已连接 DashScope ASR");
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                handleDashScopeMessage(text);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("[语音ASR] DashScope 连接失败: {}", t.getMessage());
                sendToClient("{\"type\":\"error\",\"message\":\"语音识别服务暂时不可用，请稍后重试\"}");
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.info("[语音ASR] DashScope 连接关闭: {} {}", code, reason);
            }
        });
    }

    // =========================================================
    // DashScope 协议消息
    // =========================================================

    private void sendRunTask() {
        if (dashscopeWs == null) return;

        this.taskId = UUID.randomUUID().toString().replace("-", "");
        confirmedText.setLength(0);

        JSONObject header = new JSONObject();
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        JSONObject parameters = new JSONObject();
        parameters.put("format", "pcm");
        parameters.put("sample_rate", 16000);

        JSONObject payload = new JSONObject();
        payload.put("task_group", "audio");
        payload.put("task", "asr");
        payload.put("function", "recognition");
        payload.put("model", "paraformer-realtime-v2");
        payload.put("parameters", parameters);
        payload.put("input", new JSONObject());

        JSONObject msg = new JSONObject();
        msg.put("header", header);
        msg.put("payload", payload);

        dashscopeWs.send(msg.toJSONString());
        taskStarted = true;
        log.info("[语音ASR] run-task 已发送, taskId={}", taskId);
    }

    private void sendFinishTask() {
        if (!taskStarted || dashscopeWs == null) return;
        taskStarted = false;

        JSONObject header = new JSONObject();
        header.put("action", "finish-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        JSONObject payload = new JSONObject();
        payload.put("input", new JSONObject());

        JSONObject msg = new JSONObject();
        msg.put("header", header);
        msg.put("payload", payload);

        dashscopeWs.send(msg.toJSONString());
        log.info("[语音ASR] finish-task 已发送, taskId={}", taskId);
    }

    // =========================================================
    // 处理 DashScope 响应
    // =========================================================

    private void handleDashScopeMessage(String text) {
        try {
            JSONObject response = JSON.parseObject(text);
            JSONObject header = response.getJSONObject("header");
            if (header == null) return;

            String event = header.getString("event");
            if (event == null) return;

            switch (event) {
                case "task-started" -> log.info("[语音ASR] 任务已启动");

                case "result-generated" -> {
                    JSONObject payload = response.getJSONObject("payload");
                    if (payload == null) break;
                    JSONObject output = payload.getJSONObject("output");
                    if (output == null) break;
                    JSONObject sentence = output.getJSONObject("sentence");
                    if (sentence == null) break;

                    String recognizedText = sentence.getString("text");
                    if (recognizedText == null || recognizedText.isBlank()) break;

                    // end_time > 0 表示本句识别完成（最终结果）
                    long endTime = sentence.getLongValue("end_time", 0L);
                    // 也检查 header.attributes.is_sentence_end
                    JSONObject attributes = header.getJSONObject("attributes");
                    boolean isSentenceEnd = endTime > 0
                            || (attributes != null && Boolean.TRUE.equals(attributes.getBoolean("is_sentence_end")));

                    if (isSentenceEnd) {
                        confirmedText.append(recognizedText);
                    }

                    // 向前端推送：实时文本 = 已确认 + 当前句
                    String displayText = isSentenceEnd
                            ? confirmedText.toString()
                            : confirmedText + recognizedText;

                    JSONObject result = new JSONObject();
                    result.put("type", "transcript");
                    result.put("text", displayText);
                    result.put("isFinal", isSentenceEnd);
                    sendToClient(result.toJSONString());
                }

                case "task-finished" -> {
                    log.info("[語音ASR] 任务完成");
                    JSONObject result = new JSONObject();
                    result.put("type", "finished");
                    result.put("text", confirmedText.toString());
                    sendToClient(result.toJSONString());
                }

                case "task-failed" -> {
                    String errMsg = header.getString("error_message");
                    log.error("[语音ASR] 任务失败: {}", errMsg);
                    sendToClient("{\"type\":\"error\",\"message\":\"" + errMsg + "\"}");
                }

                default -> log.debug("[语音ASR] 未处理事件: {}", event);
            }
        } catch (Exception e) {
            log.error("[语音ASR] 解析 DashScope 消息失败: {}", e.getMessage(), e);
        }
    }

    // =========================================================
    // 工具方法
    // =========================================================

    private void sendToClient(String message) {
        if (clientSession != null && clientSession.isOpen()) {
            try {
                clientSession.getBasicRemote().sendText(message);
            } catch (IOException e) {
                log.error("[语音ASR] 推送消息失败: {}", e.getMessage());
            }
        }
    }

    private void closeWithError(Session session, String reason) {
        try {
            sendToClient("{\"type\":\"error\",\"message\":\"" + reason + "\"}");
            session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
        } catch (IOException e) {
            log.error("[语音ASR] 关闭连接失败", e);
        }
    }

    private void cleanup() {
        if (dashscopeWs != null) {
            try {
                sendFinishTask();
            } catch (Exception ignored) {}
            dashscopeWs.close(1000, "cleanup");
            dashscopeWs = null;
        }
        taskStarted = false;
    }

    private static String extractParam(String queryString, String name) {
        if (queryString == null || queryString.isBlank()) return null;
        for (String pair : queryString.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) {
                return kv[1];
            }
        }
        return null;
    }
}
