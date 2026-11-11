package com.simon.MindCrew.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import lombok.Getter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 Agent 的 SSE 流式输出收集成完整 JSON 响应。
 *
 * 用于 /api/v3/chat 这种"外部接入需要一次性 JSON"的场景。
 * 不破坏 Agent 现有 SSE 接口（前端 chat 仍用 SseEmitter）。
 *
 * 工作原理：
 *   - 覆盖 SseEmitter 的 send/complete 方法
 *   - 解析 Agent 发出的 SseEventBuilder（event=token / done / rewrite）
 *   - 把 token.content 累加到 answerBuf；done.sources 收集
 *   - 调用方等 agent.execute() 返回后调 getAnswer() / getSources()
 *
 * 不依赖任何 HTTP 响应，纯内存收集。
 */
@Getter
public class CollectingEmitter extends SseEmitter {

    private final StringBuilder answerBuf = new StringBuilder();
    private final List<Map<String, Object>> sources = new ArrayList<>();
    private int inputTokens = 0;
    private int outputTokens = 0;

    public CollectingEmitter() {
        super(300_000L);
        // 把 handler 设置为空 ServerHttpResponse，防止父类初始化路径异常
        // SseEmitter 的 extendResponse / initialize 在 send 时按需求要 handler，但 send(SseEventBuilder)
        // 在 handler == null 时会抛 IllegalStateException ——
        // 所以我们用 noopInit() 避免实际 HTTP 写出（见下方 send 重写）
    }

    /** 关键 · 拦截 Agent 发的事件（agent 用的就是这个签名） */
    @Override
    public void send(SseEventBuilder builder) {
        try {
            Set<DataWithMediaType> events = builder.build();
            for (DataWithMediaType e : events) {
                Object d = e.getData();
                if (d == null) continue;
                String s = d.toString();
                if (s.startsWith("event:")) {
                    // SseEmitter.event() builder 把 event 名和 data 各自作为一个 DataWithMediaType
                    // 我们只关心 data 里的 JSON
                    continue;
                }
                tryHandleAsJsonEvent(s);
            }
        } catch (Exception ignored) {
            // 单事件失败不影响主流程
        }
    }

    /** 兼容其它 send 重载 · 直接丢给 builder 路径 */
    @Override
    public void send(Object obj) {
        if (obj instanceof SseEventBuilder b) send(b);
        else if (obj != null) tryHandleAsJsonEvent(obj.toString());
    }

    @Override
    public void send(Object obj, MediaType mediaType) { send(obj); }

    @Override
    public void complete() { /* 不发响应 */ }

    @Override
    public void completeWithError(Throwable ex) { /* 不发响应 */ }

    // ─────────────────────────────────────────────
    // 内部解析
    // ─────────────────────────────────────────────
    private void tryHandleAsJsonEvent(String raw) {
        if (raw == null || raw.isBlank()) return;
        // 不一定是 JSON · 兼容空和非 JSON 数据
        if (!raw.trim().startsWith("{") && !raw.trim().startsWith("[")) return;
        try {
            JSONObject d = JSON.parseObject(raw);
            // token 事件
            if (d.containsKey("content")) {
                Object c = d.get("content");
                if (c != null) {
                    String s = c.toString();
                    answerBuf.append(s);
                    outputTokens += Math.max(1, s.length() / 2);
                }
                return;
            }
            // done 事件（含 sources）
            if (d.containsKey("sources")) {
                Object srcs = d.get("sources");
                if (srcs instanceof JSONArray ja) {
                    for (Object o : ja) {
                        if (o instanceof Map) {
                            //noinspection unchecked
                            sources.add(new LinkedHashMap<>((Map<String, Object>) o));
                        } else if (o instanceof JSONObject jo) {
                            sources.add(new LinkedHashMap<>(jo));
                        }
                    }
                }
                return;
            }
            // rewrite 事件 · 估算 input tokens
            if (d.containsKey("original")) {
                inputTokens += Math.max(1, String.valueOf(d.get("original")).length() / 2);
            }
        } catch (Exception ignored) {}
    }

    public String getAnswer() {
        return answerBuf.toString();
    }
}
