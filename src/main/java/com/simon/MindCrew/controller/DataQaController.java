package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.service.dataqa.DataQaService;
import com.simon.MindCrew.service.dataqa.MetricCatalog;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 问数（Data Q&A）· 安全指标问答
 *
 *   GET  /api/v2/data-qa/metrics  · 白名单指标清单（登录即可）
 *   POST /api/v2/data-qa/ask      · 自然语言提问 → 意图解析 → 固定模板只读查询
 *
 * 权限：/api/v2/data-qa/** 由 SecurityConfig 的 anyRequest().authenticated() 覆盖，登录即可访问。
 * 只读，不写审计。不支持的指标抛 BusinessException(400)，由 GlobalExceptionHandler 统一返回。
 */
@RestController
@RequestMapping("/api/v2/data-qa")
@RequiredArgsConstructor
public class DataQaController {

    private final DataQaService dataQaService;

    /**
     * 指标清单：给前端指标 chip / 支持指标列表使用。
     * 返回项固定为 key/name/description/chartType/example。
     */
    @GetMapping("/metrics")
    public Result<List<Map<String, Object>>> metrics() {
        return Result.success(MetricCatalog.describeAll());
    }

    /**
     * 问一句。
     * 请求体：{"question": "...", "days": null}
     * days 可空；非空时覆盖 LLM 解析结果，越界自动钳制到 1~90。
     */
    @PostMapping("/ask")
    public Result<Map<String, Object>> ask(@RequestBody(required = false) Map<String, Object> body) {
        Object rawQuestion = body == null ? null : body.get("question");
        String question = rawQuestion == null ? null : String.valueOf(rawQuestion);
        Integer days = toInteger(body == null ? null : body.get("days"));
        return Result.success(dataQaService.ask(question, days));
    }

    /** 请求体里的 days 可能是数字/字符串/null，非法值按未传处理（由服务层钳制）。 */
    private static Integer toInteger(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
