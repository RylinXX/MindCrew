package com.simon.MindCrew.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.common.utils.JwtUtils;
import com.simon.MindCrew.entity.VoicePersona;
import com.simon.MindCrew.service.CosyVoiceTtsService;
import com.simon.MindCrew.service.VoicePersonaService;
import com.simon.MindCrew.service.VoiceTurnService;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 语音通话 WebSocket · 任务 14.5（v2 · 持续监听 + VAD 自动打断）
 *
 * 设计哲学：
 *   - 一次通话 = 一个 DashScope ASR 连接（持续 streaming）
 *   - 整个通话期间 mic PCM 全部 forward 给 ASR，避免反复 run-task 触发"Invalid action"
 *   - 句子结束（is_sentence_end）自动触发 LLM + TTS pipeline
 *   - AI 说话时收到 cancel 立即终止 → 切回 READY 继续识别
 *
 * 客户端 → 服务端
 *   JSON {type:"config", voiceId?, kbIds?}     首消息，配置音色/范围，触发 ASR 连接
 *   Binary PCM 16kHz 单声道 16-bit             麦克风音频帧（整个通话期间持续）
 *   JSON {type:"text_message", text}           键入文本（跳过 ASR 直接走 LLM）
 *   JSON {type:"cancel"}                       打断 AI（VAD 自动或手动按钮）
 *
 * 服务端 → 客户端
 *   JSON {type:"ready"}                        ASR 连接就绪，可以开始说
 *   JSON {type:"asr_partial", text}            ASR 中间结果
 *   JSON {type:"asr_final",   text}            一句话识别完成
 *   JSON {type:"thinking"}                     LLM 开始
 *   JSON {type:"llm_answer",  text}            LLM 完整回答
 *   JSON {type:"tts_start",   sampleRate}      TTS 即将开始
 *   Binary PCM 帧                              TTS 音频
 *   JSON {type:"tts_end"}                      TTS 完成
 *   JSON {type:"turn_end"}                     一轮完成，回到 READY
 *   JSON {type:"error", message}               任意阶段异常
 */
@Slf4j
@Component
@ServerEndpoint("/api/voice-call/ws")
public class VoiceCallWebSocketServer {

    // ─── 静态依赖 ───
    public static JwtUtils jwtUtils;
    public static String apiKey;
    public static OkHttpClient okHttpClient;
    public static MindCrewAgent mindCrewAgent;
    public static CosyVoiceTtsService ttsService;
    public static VoicePersonaService voicePersonaService;
    public static VoiceTurnService voiceTurnService;
    public static ExecutorService workerExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "voice-call-worker");
        t.setDaemon(true);
        return t;
    });

    private static final String DASHSCOPE_WS_URL =
            "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";

    // ─── 单连接状态 ───
    private Session client;
    private String userId;
    private Long userIdLong;
    private VoicePersona voice;
    private List<Long> kbIds;

    private volatile State state = State.IDLE;

    /** 整通话生命周期内唯一的 ASR ws */
    private WebSocket asrWs;
    private String asrTaskId;
    private volatile boolean asrTaskStarted = false;
    private final StringBuilder asrText = new StringBuilder();

    /** 当前流式 TTS 会话 · 任务 14 性能优化：边出 token 边合成 */
    private final AtomicReference<CosyVoiceTtsService.StreamingSession> ttsSession = new AtomicReference<>();
    /** TTS 是否已对当前 turn 发送过 tts_start（在第一帧 PCM 到达时发） */
    private final java.util.concurrent.atomic.AtomicBoolean ttsStartedSentForTurn =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 通话级生命周期内是否已收到 config */
    private volatile boolean configured = false;

    enum State { IDLE, READY, THINKING, SPEAKING }

    // =========================================================
    // WS 生命周期
    // =========================================================

    @OnOpen
    public void onOpen(Session session) {
        this.client = session;
        String token = extractParam(session.getQueryString(), "token");
        if (token == null || !jwtUtils.validateToken(token)) {
            sendJson("{\"type\":\"error\",\"message\":\"无效的 Token，请重新登录\"}");
            try { session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, "unauthenticated")); }
            catch (IOException ignored) {}
            return;
        }
        try {
            Long uid = jwtUtils.getUserId(token);
            this.userIdLong = uid;
            this.userId = uid == null ? null : String.valueOf(uid);
        } catch (Exception e) {
            this.userId = null;
        }
        log.info("[VoiceCall] 连接 · user={} session={}", userId, session.getId());
    }

    @OnMessage
    public void onText(String message, Session session) {
        if (message == null) return;
        JSONObject msg;
        try { msg = JSON.parseObject(message); }
        catch (Exception e) { return; }
        String type = msg.getString("type");
        if (type == null) return;

        switch (type) {
            case "config" -> handleConfig(msg);
            case "text_message" -> handleTextMessage(msg.getString("text"));
            case "cancel" -> handleCancel();
            default -> log.debug("[VoiceCall] 未知文本消息: {}", type);
        }
    }

    @OnMessage
    public void onBinary(ByteBuffer buffer, Session session) {
        // 整通话持续把 PCM 送给 DashScope ASR；后端按 state 决定如何处理识别结果
        if (asrWs == null || !asrTaskStarted) return;
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try { asrWs.send(ByteString.of(bytes)); } catch (Exception ignored) {}
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        log.info("[VoiceCall] 关闭 · user={} reason={}", userId, reason.getReasonPhrase());
        cleanupAll();
    }

    @OnError
    public void onError(Session session, Throwable error) {
        log.error("[VoiceCall] 错误 · user={} err={}", userId, error.getMessage());
        cleanupAll();
    }

    // =========================================================
    // 处理客户端事件
    // =========================================================

    private void handleConfig(JSONObject msg) {
        if (configured) {
            log.warn("[VoiceCall] 重复 config，忽略");
            return;
        }
        Long vId = msg.getLong("voiceId");
        this.voice = voicePersonaService.getOrDefault(vId);
        if (this.voice == null) {
            sendJson("{\"type\":\"error\",\"message\":\"系统没有可用音色，请先跑 sql/voice-persona-schema.sql\"}");
            return;
        }
        if (msg.containsKey("kbIds")) {
            try { this.kbIds = msg.getJSONArray("kbIds").toJavaList(Long.class); }
            catch (Exception e) { this.kbIds = List.of(); }
        } else {
            this.kbIds = List.of();
        }
        configured = true;
        // 立即建立 ASR 长连接（整通话期间复用）
        connectAsr();
    }

    private void handleTextMessage(String text) {
        if (text == null || text.isBlank()) return;
        cancelTtsIfAny();
        startThinkingPipeline(text);
    }

    private void handleCancel() {
        log.debug("[VoiceCall] cancel · state={}", state);
        cancelTtsIfAny();
        if (state == State.SPEAKING || state == State.THINKING) {
            state = State.READY;
            sendJson("{\"type\":\"turn_end\"}");
        }
    }

    // =========================================================
    // ASR · 整通话生命周期内单连接
    // =========================================================

    private void connectAsr() {
        Request req = new Request.Builder()
                .url(DASHSCOPE_WS_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-DataInspection", "enable")
                .build();
        this.asrTaskId = UUID.randomUUID().toString().replace("-", "");
        asrText.setLength(0);

        asrWs = okHttpClient.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.info("[VoiceCall.ASR] connected · taskId={}", asrTaskId);
                sendAsrRunTask();
            }
            @Override
            public void onMessage(WebSocket ws, String text) { handleAsrMessage(text); }
            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("[VoiceCall.ASR] failure: {}", t.getMessage());
                sendJson("{\"type\":\"error\",\"message\":\"语音识别服务不可用，请挂断重试\"}");
            }
            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.info("[VoiceCall.ASR] closed · code={} reason={}", code, reason);
                asrTaskStarted = false;
            }
        });
    }

    private void sendAsrRunTask() {
        JSONObject header = new JSONObject();
        header.put("action", "run-task");
        header.put("task_id", asrTaskId);
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
        asrWs.send(msg.toJSONString());
        asrTaskStarted = true;
    }

    private void handleAsrMessage(String text) {
        try {
            JSONObject resp = JSON.parseObject(text);
            JSONObject header = resp.getJSONObject("header");
            if (header == null) return;
            String event = header.getString("event");
            if (event == null) return;

            switch (event) {
                case "task-started" -> {
                    state = State.READY;
                    JSONObject ready = new JSONObject();
                    ready.put("type", "ready");
                    ready.put("voiceId", voice.getId());
                    ready.put("voiceName", voice.getName());
                    sendJson(ready.toJSONString());
                }
                case "result-generated" -> {
                    // AI 正在说话或思考时收到的识别结果忽略（前端通过 VAD 已触发 cancel）
                    if (state != State.READY) return;

                    JSONObject payload = resp.getJSONObject("payload");
                    if (payload == null) break;
                    JSONObject output = payload.getJSONObject("output");
                    if (output == null) break;
                    JSONObject sentence = output.getJSONObject("sentence");
                    if (sentence == null) break;
                    String recog = sentence.getString("text");
                    if (recog == null || recog.isBlank()) break;

                    long endTime = sentence.getLongValue("end_time", 0L);
                    JSONObject attrs = header.getJSONObject("attributes");
                    boolean isSentenceEnd = endTime > 0
                            || (attrs != null && Boolean.TRUE.equals(attrs.getBoolean("is_sentence_end")));

                    if (isSentenceEnd) {
                        // 一句话识别完成 → 累积到 asrText，触发 LLM
                        asrText.append(recog);
                        String full = asrText.toString().trim();
                        JSONObject out = new JSONObject();
                        out.put("type", "asr_final");
                        out.put("text", full);
                        sendJson(out.toJSONString());
                        if (!full.isBlank()) {
                            startThinkingPipeline(full);
                            asrText.setLength(0);
                        }
                    } else {
                        // 中间结果：拼当前句
                        String display = asrText + recog;
                        JSONObject out = new JSONObject();
                        out.put("type", "asr_partial");
                        out.put("text", display);
                        sendJson(out.toJSONString());
                    }
                }
                case "task-failed" -> {
                    String errMsg = header.getString("error_message");
                    String errCode = header.getString("error_code");
                    // NO_VALID_AUDIO_ERROR 是没采到音频，不算致命；DashScope 会自动 finish task
                    // 我们在客户端层不再用断/连重启 ASR；如要复用，重新调 connectAsr
                    log.warn("[VoiceCall.ASR] task-failed: code={} msg={}", errCode, errMsg);
                    asrTaskStarted = false;
                    if ("NO_VALID_AUDIO_ERROR".equals(errCode)) {
                        // 静默 + 自动重连，体验上像没发生过
                        log.info("[VoiceCall.ASR] 静音超时，自动重连 ASR");
                        try { asrWs.close(1000, "no-audio"); } catch (Exception ignored) {}
                        asrWs = null;
                        connectAsr();
                    } else {
                        sendJson("{\"type\":\"error\",\"message\":\"ASR 失败: " + escape(errMsg) + "\"}");
                    }
                }
                case "task-finished" -> {
                    log.info("[VoiceCall.ASR] task-finished · 主动重连维持长连接");
                    asrTaskStarted = false;
                    try { asrWs.close(1000, "rotate"); } catch (Exception ignored) {}
                    asrWs = null;
                    connectAsr();
                }
                default -> { /* 忽略 */ }
            }
        } catch (Exception e) {
            log.error("[VoiceCall.ASR] parse error", e);
        }
    }

    private void stopAsr() {
        if (asrWs != null) {
            try {
                if (asrTaskStarted) {
                    JSONObject header = new JSONObject();
                    header.put("action", "finish-task");
                    header.put("task_id", asrTaskId);
                    header.put("streaming", "duplex");
                    JSONObject payload = new JSONObject();
                    payload.put("input", new JSONObject());
                    JSONObject msg = new JSONObject();
                    msg.put("header", header);
                    msg.put("payload", payload);
                    asrWs.send(msg.toJSONString());
                }
            } catch (Exception ignored) {}
            try { asrWs.close(1000, "stop"); } catch (Exception ignored) {}
            asrWs = null;
        }
        asrTaskStarted = false;
    }

    // =========================================================
    // LLM + TTS 管线
    // =========================================================

    /**
     * 任务 14 性能优化（C+D+F）：流式 LLM + 句级 TTS
     *  - VoiceTurnService 用 qwen-turbo + 轻量 RAG（top-3 chunks）
     *  - LLM token 流式回调 → 累积到 sentenceBuf
     *  - 检测到句末（。！？.!?\n） → 立刻 feed 给 TTS streaming session
     *  - 第一帧 PCM 到达 = 实质性"AI 在说话"，发 tts_start 给前端
     *  - LLM 完成 → flush 残留 + finish() TTS
     */
    private void startThinkingPipeline(String userText) {
        if (state == State.THINKING || state == State.SPEAKING) {
            log.debug("[VoiceCall] 已有进行中的 turn，忽略本次触发");
            return;
        }
        state = State.THINKING;
        ttsStartedSentForTurn.set(false);
        sendJson("{\"type\":\"thinking\"}");

        final long t0 = System.currentTimeMillis();
        final int sr = voice.getSampleRate() == null ? 22050 : voice.getSampleRate();
        final StringBuilder fullAnswer = new StringBuilder();
        final StringBuilder sentenceBuf = new StringBuilder();
        final long[] firstTokenAt = {0L};

        // 1) 开一个 TTS 流式会话（先建好连接，等 LLM 来填）
        CosyVoiceTtsService.StreamingSession tts = ttsService.openStreamingSession(
                voice,
                pcm -> {
                    // 第一帧 PCM 到达 → 发 tts_start
                    if (ttsStartedSentForTurn.compareAndSet(false, true)) {
                        state = State.SPEAKING;
                        JSONObject startMsg = new JSONObject();
                        startMsg.put("type", "tts_start");
                        startMsg.put("sampleRate", sr);
                        sendJson(startMsg.toJSONString());
                    }
                    sendBinary(pcm);
                },
                () -> {
                    sendJson("{\"type\":\"tts_end\"}");
                    if (state == State.SPEAKING) {
                        state = State.READY;
                        sendJson("{\"type\":\"turn_end\"}");
                    }
                    ttsSession.set(null);
                },
                err -> {
                    log.error("[VoiceCall.TTS] error", err);
                    sendJson("{\"type\":\"error\",\"message\":\"TTS 失败: " + escape(err.getMessage()) + "\"}");
                    if (state == State.SPEAKING || state == State.THINKING) {
                        state = State.READY;
                        sendJson("{\"type\":\"turn_end\"}");
                    }
                    ttsSession.set(null);
                }
        );
        ttsSession.set(tts);

        // 2) 启动流式 LLM
        voiceTurnService.streamAnswer(
                userText,
                kbIds == null ? List.of() : kbIds,
                token -> {
                    if (firstTokenAt[0] == 0L) {
                        firstTokenAt[0] = System.currentTimeMillis();
                        log.info("[VoiceCall] 首 token 延迟 {}ms", firstTokenAt[0] - t0);
                    }
                    if (state == State.READY || state == State.IDLE) {
                        // 用户已打断，丢弃后续
                        return;
                    }
                    fullAnswer.append(token);
                    sentenceBuf.append(token);
                    // 句末判定 · 含中英文标点 + 换行
                    flushCompleteSentences(sentenceBuf, tts);
                },
                () -> {
                    // LLM 完成
                    long elapsed = System.currentTimeMillis() - t0;
                    log.info("[VoiceCall] LLM 完成 · total={}ms answerLen={}", elapsed, fullAnswer.length());
                    if (state == State.READY || state == State.IDLE) {
                        return;  // 已打断
                    }
                    // 发完整答案给前端字幕
                    JSONObject ans = new JSONObject();
                    ans.put("type", "llm_answer");
                    ans.put("text", fullAnswer.toString());
                    sendJson(ans.toJSONString());

                    // 残留 buffer flush
                    if (sentenceBuf.length() > 0) {
                        tts.feed(sentenceBuf.toString());
                        sentenceBuf.setLength(0);
                    }
                    tts.finish();
                },
                err -> {
                    log.error("[VoiceCall.LLM] 流式失败", err);
                    sendJson("{\"type\":\"error\",\"message\":\"AI 思考失败: " + escape(err.getMessage()) + "\"}");
                    tts.cancel();
                    ttsSession.set(null);
                    if (state == State.THINKING || state == State.SPEAKING) {
                        state = State.READY;
                        sendJson("{\"type\":\"turn_end\"}");
                    }
                }
        );
    }

    /** 检测 sentenceBuf 中已结束的完整句子，feed 给 TTS · 句末按中英文标点 / 换行 */
    private void flushCompleteSentences(StringBuilder buf, CosyVoiceTtsService.StreamingSession tts) {
        int lastEnd = -1;
        for (int i = 0; i < buf.length(); i++) {
            char c = buf.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?'
                    || c == '；' || c == ';' || c == '\n') {
                lastEnd = i;
            }
        }
        // 句子至少 8 字才 flush，避免极短碎片化（"嗯。"会拖累节奏）
        if (lastEnd >= 0 && lastEnd >= 7) {
            String sentence = buf.substring(0, lastEnd + 1);
            tts.feed(sentence);
            buf.delete(0, lastEnd + 1);
        }
    }

    private void cancelTtsIfAny() {
        CosyVoiceTtsService.StreamingSession s = ttsSession.getAndSet(null);
        if (s != null) {
            log.debug("[VoiceCall] 取消 TTS · taskId={}", s.taskId);
            s.cancel();
        }
    }

    // =========================================================
    // 发送
    // =========================================================

    private void sendJson(String msg) {
        if (client == null || !client.isOpen()) return;
        synchronized (this) {
            try { client.getBasicRemote().sendText(msg); }
            catch (Exception e) { log.warn("[VoiceCall] send text 失败: {}", e.getMessage()); }
        }
    }

    private void sendBinary(byte[] data) {
        if (client == null || !client.isOpen()) return;
        synchronized (this) {
            try { client.getBasicRemote().sendBinary(ByteBuffer.wrap(data)); }
            catch (Exception e) { log.warn("[VoiceCall] send binary 失败: {}", e.getMessage()); }
        }
    }

    private void cleanupAll() {
        cancelTtsIfAny();
        stopAsr();
        state = State.IDLE;
        configured = false;
    }

    private static String extractParam(String queryString, String name) {
        if (queryString == null || queryString.isBlank()) return null;
        for (String pair : queryString.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && name.equals(kv[0])) return kv[1];
        }
        return null;
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
