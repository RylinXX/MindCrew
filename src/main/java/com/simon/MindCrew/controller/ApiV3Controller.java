package com.simon.MindCrew.controller;

import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.ApiCallLog;
import com.simon.MindCrew.entity.ApiKey;
import com.simon.MindCrew.security.ApiKeyContext;
import com.simon.MindCrew.mcp.DocSearchTool;
import com.simon.MindCrew.service.rag.RetrievedChunk;
import com.simon.MindCrew.service.ApiKeyService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 对外开放 API · 任务 11
 *
 * 路径：/api/v3/**
 * 鉴权：Authorization: Bearer mk_xxxxxxxx （ApiKeyAuthFilter 已校验）
 * 计费：每次成功调用 month_used + 1，超额返回 429
 *
 * 暴露接口：
 *   POST /api/v3/chat     · 非流式问答（一次返 JSON，对接最简单）
 *   POST /api/v3/search   · 纯检索（不走 LLM 生成，按调用次数计费）
 *   GET  /api/v3/me       · 当前 key 信息 + 配额 / 剩余次数（接入方自查）
 *   GET  /api/v3/kbs      · 当前 key 可访问的 KB 列表（供接入方下拉用）
 */
@Slf4j
@RestController
@RequestMapping("/api/v3")
@RequiredArgsConstructor
public class ApiV3Controller {

    private final ApiKeyService apiKeyService;
    private final MindCrewAgent agent;
    private final DocSearchTool docSearchTool;

    // ─────────────────────────────────────────────
    // 接入方自查：当前 key 信息
    // ─────────────────────────────────────────────
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        ApiKey k = ApiKeyContext.current();
        Map<String, Object> m = new HashMap<>();
        m.put("id", k.getId());
        m.put("name", k.getName());
        m.put("monthlyQuota", k.getMonthlyQuota());
        m.put("monthUsed",    k.getMonthUsed());
        m.put("remaining",    Math.max(0, k.getMonthlyQuota() - k.getMonthUsed()));
        m.put("totalCalls",   k.getTotalCalls());
        m.put("allowedKbIds", apiKeyService.getAllowedKbIds(k));
        m.put("expireAt",     k.getExpireAt());
        return Result.success(m);
    }

    @GetMapping("/kbs")
    public Result<List<Long>> kbs() {
        return Result.success(apiKeyService.getAllowedKbIds(ApiKeyContext.current()));
    }

    // ─────────────────────────────────────────────
    // 问答 · 非流式
    // ─────────────────────────────────────────────
    @Data
    public static class ChatRequest {
        private String question;
        private Long kbId;          // 必填 · 11.6 · 强制指定走哪个 KB
        private List<Long> kbIds;   // 可选 · 多 KB 联合检索；与 kbId 二选一
    }

    @PostMapping("/chat")
    public ResponseEntity<?> chat(@RequestBody ChatRequest req, HttpServletRequest http) {
        long t0 = System.currentTimeMillis();
        ApiKey k = ApiKeyContext.current();
        if (req == null || req.getQuestion() == null || req.getQuestion().isBlank()) {
            return logAndReturn(k, "/v3/chat", null, req == null ? null : req.getQuestion(),
                    400, "question 必填", http, t0, 0, 0);
        }

        // 1) 解析 KB 列表
        List<Long> kbs;
        if (req.getKbId() != null) {
            kbs = List.of(req.getKbId());
        } else if (req.getKbIds() != null && !req.getKbIds().isEmpty()) {
            kbs = req.getKbIds();
        } else {
            return logAndReturn(k, "/v3/chat", null, req.getQuestion(),
                    400, "必须指定 kbId 或 kbIds", http, t0, 0, 0);
        }

        // 2) 11.6 · 校验每个 KB 都在 allowed_kb_ids 内
        for (Long kbId : kbs) {
            if (!apiKeyService.canAccessKb(k, kbId)) {
                return logAndReturn(k, "/v3/chat", kbId, req.getQuestion(),
                        403, "该 API key 无权访问 KB " + kbId, http, t0, 0, 0);
            }
        }

        // 3) 真实走 Agent · 拼一个内部 user_id 字符串（用 api_key:<id> 占位）
        try {
            // SSE Emitter 包装：Agent 流式输出收集到内存 → 整体返 JSON
            CollectingEmitter collector = new CollectingEmitter();
            agent.execute("apiKey:" + k.getId(), null, req.getQuestion(), kbs, List.of(), collector);
            String answer = collector.getAnswer();
            List<Map<String, Object>> sources = collector.getSources();
            long elapsed = System.currentTimeMillis() - t0;

            // 4) 计数 + 日志
            apiKeyService.chargeOne(k.getId());
            ApiCallLog l = buildLog(k, "/v3/chat", kbs.get(0), req.getQuestion(), 200, http, t0,
                    collector.getInputTokens(), collector.getOutputTokens(), null);
            apiKeyService.logCallAsync(l);

            Map<String, Object> out = new HashMap<>();
            out.put("answer", answer);
            out.put("sources", sources);
            out.put("elapsedMs", elapsed);
            out.put("inputTokens",  collector.getInputTokens());
            out.put("outputTokens", collector.getOutputTokens());
            return ResponseEntity.ok(Result.success(out));
        } catch (Exception e) {
            log.error("[v3/chat] 异常 keyId={} err={}", k.getId(), e.getMessage(), e);
            return logAndReturn(k, "/v3/chat", kbs.isEmpty() ? null : kbs.get(0), req.getQuestion(),
                    500, "Internal error: " + e.getMessage(), http, t0, 0, 0);
        }
    }

    // ─────────────────────────────────────────────
    // 纯检索 · 不走 LLM 生成
    // ─────────────────────────────────────────────
    @Data
    public static class SearchRequest {
        private String query;
        private Long kbId;
        private Integer topK;
    }

    @PostMapping("/search")
    public ResponseEntity<?> search(@RequestBody SearchRequest req, HttpServletRequest http) {
        long t0 = System.currentTimeMillis();
        ApiKey k = ApiKeyContext.current();
        if (req == null || req.getQuery() == null || req.getQuery().isBlank()) {
            return logAndReturn(k, "/v3/search", null, null,
                    400, "query 必填", http, t0, 0, 0);
        }
        if (req.getKbId() == null) {
            return logAndReturn(k, "/v3/search", null, req.getQuery(),
                    400, "kbId 必填", http, t0, 0, 0);
        }
        if (!apiKeyService.canAccessKb(k, req.getKbId())) {
            return logAndReturn(k, "/v3/search", req.getKbId(), req.getQuery(),
                    403, "无权访问该 KB", http, t0, 0, 0);
        }

        int topK = req.getTopK() == null ? 5 : Math.min(req.getTopK(), 50);
        try {
            // 复用现有 DocSearchTool · 纯向量检索（不走 LLM 生成）
            List<RetrievedChunk> chunks = docSearchTool.searchDocs(req.getQuery(), topK, List.of(req.getKbId()));
            List<Map<String, Object>> hits = new java.util.ArrayList<>();
            for (RetrievedChunk c : chunks) {
                Map<String, Object> h = new HashMap<>();
                h.put("id",          c.getId());
                h.put("content",     c.getContent());
                h.put("sourceName",  c.getSourceName());
                h.put("kbId",        c.getKnowledgeBaseId());
                h.put("score",       c.getScore());
                h.put("chapter",     c.getChapter());
                h.put("pageNumber",  c.getPageNumber());
                hits.add(h);
            }
            apiKeyService.chargeOne(k.getId());
            ApiCallLog l = buildLog(k, "/v3/search", req.getKbId(), req.getQuery(), 200, http, t0, 0, 0, null);
            apiKeyService.logCallAsync(l);

            Map<String, Object> out = new HashMap<>();
            out.put("hits", hits);
            out.put("topK", topK);
            out.put("elapsedMs", System.currentTimeMillis() - t0);
            return ResponseEntity.ok(Result.success(out));
        } catch (Exception e) {
            log.error("[v3/search] 异常 keyId={} err={}", k.getId(), e.getMessage(), e);
            return logAndReturn(k, "/v3/search", req.getKbId(), req.getQuery(),
                    500, "Internal error: " + e.getMessage(), http, t0, 0, 0);
        }
    }

    // ─────────────────────────────────────────────
    // 日志 + 错误响应工具
    // ─────────────────────────────────────────────
    private ResponseEntity<Result<Void>> logAndReturn(ApiKey k, String api, Long kbId, String question,
                                                     int statusCode, String message,
                                                     HttpServletRequest http, long t0,
                                                     int inputTokens, int outputTokens) {
        ApiCallLog l = buildLog(k, api, kbId, question, statusCode, http, t0, inputTokens, outputTokens, message);
        apiKeyService.logCallAsync(l);
        return ResponseEntity.status(statusCode).body(Result.error(statusCode, message));
    }

    private ApiCallLog buildLog(ApiKey k, String api, Long kbId, String question, int statusCode,
                                 HttpServletRequest http, long t0,
                                 int inputTokens, int outputTokens, String errorMsg) {
        ApiCallLog l = new ApiCallLog();
        l.setKeyId(k.getId());
        l.setKbId(kbId);
        l.setApi(api);
        l.setQuestion(question == null ? null : question.substring(0, Math.min(500, question.length())));
        l.setStatusCode(statusCode);
        l.setInputTokens(inputTokens);
        l.setOutputTokens(outputTokens);
        l.setCostCny(BigDecimal.ZERO);   // 任务 13 接入定价后补
        l.setLatencyMs((int)(System.currentTimeMillis() - t0));
        l.setIp(http == null ? null : http.getRemoteAddr());
        l.setUserAgent(http == null ? null : http.getHeader("User-Agent"));
        l.setErrorMsg(errorMsg == null ? null : errorMsg.substring(0, Math.min(500, errorMsg.length())));
        l.setCalledAt(LocalDateTime.now());
        return l;
    }
}
