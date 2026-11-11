package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.simon.MindCrew.entity.VoicePersona;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import okio.ByteString;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * CosyVoice TTS · DashScope WebSocket 协议封装
 *
 * 提供两种调用：
 *   1. synthesizeBlocking(text, voice) → 阻塞到合成完成，返回完整 PCM 字节
 *      用于 chat 答案 🔊 按钮（一次性下载播放）
 *   2. synthesizeStreaming(text, voice, onAudioChunk, onComplete, onError) → 边收边回调
 *      用于实时通话（边生成边播）
 *
 * 务实约束：
 *   - 失败抛 RuntimeException，不返回静音兜底（让上层提示用户）
 *   - 单连接生命周期 = 一次 run-task → finish-task；不复用连接（DashScope 单 task 单连接）
 *   - 取消：调用方持有 SynthesisHandle，可主动 cancel
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CosyVoiceTtsService {

    private static final String WS_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference/";
    /** PCM 输出，便于通话场景流式播放 */
    private static final String DEFAULT_FORMAT = "pcm";
    private static final int DEFAULT_SAMPLE_RATE = 22050;

    private final OkHttpClient ttsClient = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.MINUTES)
            .build();

    @Value("${llm.api-key}")
    private String apiKey;

    // ─────────────────────────────────────────────
    // 阻塞合成（短文本场景）
    // ─────────────────────────────────────────────

    public byte[] synthesizeBlocking(String text, VoicePersona voice) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("text 不能为空");
        if (voice == null) throw new IllegalArgumentException("voice 不能为空");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        CompletableFuture<Void> done = new CompletableFuture<>();

        SynthesisHandle h = synthesizeStreaming(text, voice,
                chunk -> {
                    try { buf.write(chunk); } catch (IOException ignored) {}
                },
                () -> done.complete(null),
                err -> done.completeExceptionally(err)
        );

        try {
            done.get(60, TimeUnit.SECONDS);
        } catch (TimeoutException te) {
            h.cancel();
            throw new RuntimeException("TTS 合成超时（60s）", te);
        } catch (Exception e) {
            h.cancel();
            throw new RuntimeException("TTS 合成失败: " + (e.getCause() != null ? e.getCause().getMessage() : e.getMessage()), e);
        }
        return buf.toByteArray();
    }

    // ─────────────────────────────────────────────
    // 流式合成
    // ─────────────────────────────────────────────

    /**
     * 启动流式合成。
     *
     * @param onAudioChunk 每收到一段 PCM 字节回调（线程不固定）
     * @param onComplete   合成完成（task-finished）
     * @param onError      失败回调
     * @return 句柄，可 cancel
     */
    public SynthesisHandle synthesizeStreaming(
            String text,
            VoicePersona voice,
            Consumer<byte[]> onAudioChunk,
            Runnable onComplete,
            Consumer<Throwable> onError) {

        String taskId = UUID.randomUUID().toString().replace("-", "");
        SynthesisHandle handle = new SynthesisHandle(taskId);

        Request req = new Request.Builder()
                .url(WS_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-DataInspection", "enable")
                .build();

        WebSocket ws = ttsClient.newWebSocket(req, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.debug("[TTS] WS connected · taskId={}", taskId);
                ws.send(buildRunTask(taskId, voice));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                JSONObject msg = JSON.parseObject(text);
                JSONObject header = msg.getJSONObject("header");
                if (header == null) return;
                String event = header.getString("event");
                if (event == null) return;
                switch (event) {
                    case "task-started" -> {
                        // 把要合成的文本发过去
                        ws.send(buildContinueTask(taskId, handle.textToSynthesize));
                        ws.send(buildFinishTask(taskId));
                    }
                    case "task-finished" -> {
                        log.debug("[TTS] task-finished · taskId={}", taskId);
                        ws.close(1000, "done");
                        if (!handle.cancelled.get()) safeRun(onComplete);
                    }
                    case "task-failed" -> {
                        String errMsg = header.getString("error_message");
                        log.warn("[TTS] task-failed · taskId={} err={}", taskId, errMsg);
                        ws.close(1000, "failed");
                        safeCall(onError, new RuntimeException("DashScope TTS 失败: " + errMsg));
                    }
                    default -> { /* result-generated 等忽略文本字段 */ }
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                if (handle.cancelled.get()) return;
                byte[] arr = bytes.toByteArray();
                if (arr.length == 0) return;
                try {
                    onAudioChunk.accept(arr);
                } catch (Exception e) {
                    log.warn("[TTS] onAudioChunk 回调异常: {}", e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("[TTS] WS failure · taskId={} err={}", taskId, t.getMessage());
                safeCall(onError, new RuntimeException("TTS 连接失败: " + t.getMessage(), t));
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.debug("[TTS] WS closed · taskId={} code={} reason={}", taskId, code, reason);
            }
        });

        handle.ws = ws;
        handle.textToSynthesize = text;
        return handle;
    }

    // ─────────────────────────────────────────────
    // 协议帧
    // ─────────────────────────────────────────────

    private String buildRunTask(String taskId, VoicePersona v) {
        JSONObject header = new JSONObject();
        header.put("action", "run-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        JSONObject parameters = new JSONObject();
        parameters.put("text_type", "PlainText");
        parameters.put("voice", v.getVoiceId());
        parameters.put("format", DEFAULT_FORMAT);
        parameters.put("sample_rate", v.getSampleRate() == null ? DEFAULT_SAMPLE_RATE : v.getSampleRate());
        parameters.put("volume", 50);
        parameters.put("rate", 1.0);
        parameters.put("pitch", 1.0);

        JSONObject payload = new JSONObject();
        payload.put("task_group", "audio");
        payload.put("task", "tts");
        payload.put("function", "SpeechSynthesizer");
        payload.put("model", v.getModel() == null ? "cosyvoice-v2" : v.getModel());
        payload.put("parameters", parameters);
        payload.put("input", new JSONObject());

        JSONObject msg = new JSONObject();
        msg.put("header", header);
        msg.put("payload", payload);
        return msg.toJSONString();
    }

    private String buildContinueTask(String taskId, String text) {
        JSONObject header = new JSONObject();
        header.put("action", "continue-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        JSONObject input = new JSONObject();
        input.put("text", text);

        JSONObject payload = new JSONObject();
        payload.put("input", input);

        JSONObject msg = new JSONObject();
        msg.put("header", header);
        msg.put("payload", payload);
        return msg.toJSONString();
    }

    private String buildFinishTask(String taskId) {
        JSONObject header = new JSONObject();
        header.put("action", "finish-task");
        header.put("task_id", taskId);
        header.put("streaming", "duplex");

        JSONObject payload = new JSONObject();
        payload.put("input", new JSONObject());

        JSONObject msg = new JSONObject();
        msg.put("header", header);
        msg.put("payload", payload);
        return msg.toJSONString();
    }

    // ─────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────

    private void safeRun(Runnable r) { if (r != null) try { r.run(); } catch (Exception ignored) {} }
    private void safeCall(Consumer<Throwable> c, Throwable t) { if (c != null) try { c.accept(t); } catch (Exception ignored) {} }

    // ─────────────────────────────────────────────
    // Duplex 流式会话：支持多次 feed(text) → finish()
    // 用于"边出边播"：LLM 流式输出，每句 feed 一次，TTS 边收边吐音频
    // ─────────────────────────────────────────────

    public StreamingSession openStreamingSession(
            VoicePersona voice,
            Consumer<byte[]> onAudioChunk,
            Runnable onComplete,
            Consumer<Throwable> onError) {

        String taskId = UUID.randomUUID().toString().replace("-", "");
        StreamingSession session = new StreamingSession(taskId);

        Request req = new Request.Builder()
                .url(WS_URL)
                .header("Authorization", "Bearer " + apiKey)
                .header("X-DashScope-DataInspection", "enable")
                .build();

        WebSocket ws = ttsClient.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket ws, Response response) {
                log.debug("[TTS-stream] connected · taskId={}", taskId);
                ws.send(buildRunTask(taskId, voice));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                JSONObject msg = JSON.parseObject(text);
                JSONObject header = msg.getJSONObject("header");
                if (header == null) return;
                String event = header.getString("event");
                if (event == null) return;
                switch (event) {
                    case "task-started" -> {
                        session.taskStarted.set(true);
                        // 把启动前 feed 进来的积压文本一次性发出
                        synchronized (session.pendingBuf) {
                            for (String pending : session.pendingBuf) {
                                if (pending != null && !pending.isEmpty()) {
                                    ws.send(buildContinueTask(taskId, pending));
                                }
                            }
                            session.pendingBuf.clear();
                        }
                        // 如果 finish 已被 user 提前调，立刻收尾
                        if (session.finishRequested.get()) {
                            ws.send(buildFinishTask(taskId));
                        }
                    }
                    case "task-finished" -> {
                        log.debug("[TTS-stream] task-finished · taskId={}", taskId);
                        ws.close(1000, "done");
                        if (!session.cancelled.get()) safeRun(onComplete);
                    }
                    case "task-failed" -> {
                        String errMsg = header.getString("error_message");
                        log.warn("[TTS-stream] task-failed · taskId={} err={}", taskId, errMsg);
                        ws.close(1000, "failed");
                        safeCall(onError, new RuntimeException("DashScope TTS 失败: " + errMsg));
                    }
                    default -> { /* result-generated 忽略 */ }
                }
            }

            @Override
            public void onMessage(WebSocket ws, ByteString bytes) {
                if (session.cancelled.get()) return;
                byte[] arr = bytes.toByteArray();
                if (arr.length == 0) return;
                try { onAudioChunk.accept(arr); }
                catch (Exception e) { log.warn("[TTS-stream] onAudioChunk 异常: {}", e.getMessage()); }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("[TTS-stream] WS failure · taskId={} err={}", taskId, t.getMessage());
                safeCall(onError, new RuntimeException("TTS 连接失败: " + t.getMessage(), t));
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.debug("[TTS-stream] WS closed · taskId={} code={} reason={}", taskId, code, reason);
            }
        });

        session.ws = ws;
        return session;
    }

    public class StreamingSession {
        public final String taskId;
        volatile WebSocket ws;
        final java.util.concurrent.atomic.AtomicBoolean taskStarted = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean finishRequested = new java.util.concurrent.atomic.AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
        /** 在 task-started 之前 feed 进来的文本暂存 */
        final List<String> pendingBuf = new ArrayList<>();

        public StreamingSession(String taskId) { this.taskId = taskId; }

        /** 追加一段要合成的文本 · 线程安全 */
        public void feed(String text) {
            if (cancelled.get() || finishRequested.get() || text == null || text.isEmpty()) return;
            if (!taskStarted.get()) {
                synchronized (pendingBuf) { pendingBuf.add(text); }
                return;
            }
            try { ws.send(buildContinueTask(taskId, text)); }
            catch (Exception e) { log.warn("[TTS-stream] feed 失败: {}", e.getMessage()); }
        }

        /** 告诉服务端不会再有新文本，等当前剩余音频出完即可关闭 */
        public void finish() {
            if (cancelled.get() || !finishRequested.compareAndSet(false, true)) return;
            if (!taskStarted.get()) {
                // 等 task-started 后会自动 finish
                return;
            }
            try { ws.send(buildFinishTask(taskId)); }
            catch (Exception e) { log.warn("[TTS-stream] finish 失败: {}", e.getMessage()); }
        }

        public void cancel() {
            cancelled.set(true);
            if (ws != null) {
                try { ws.close(1000, "cancel"); } catch (Exception ignored) {}
            }
        }
    }

    public static class SynthesisHandle {
        public final String taskId;
        volatile WebSocket ws;
        volatile String textToSynthesize;
        final java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);

        public SynthesisHandle(String taskId) { this.taskId = taskId; }

        /** 主动取消（用户打断 / 切话题） */
        public void cancel() {
            cancelled.set(true);
            if (ws != null) {
                try { ws.close(1000, "cancel"); } catch (Exception ignored) {}
            }
        }
    }
}
