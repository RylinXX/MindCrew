package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.service.retrieval.RetrievalAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 检索分析 · 运营接口（只读）
 *
 * 全部接口要求 ADMIN / AUDITOR 角色；不同步写审计日志。
 */
@RestController
@RequestMapping("/api/v2/retrieval")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
public class RetrievalAnalyticsController {

    private final RetrievalAnalyticsService retrievalAnalyticsService;

    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 总览 KPI */
    @GetMapping("/overview")
    public Result<Map<String, Object>> overview(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return Result.success(retrievalAnalyticsService.overview(days));
    }

    /** 按天趋势（补零、升序） */
    @GetMapping("/trend")
    public Result<List<Map<String, Object>>> trend(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return Result.success(retrievalAnalyticsService.trend(days));
    }

    /** 工具分布 */
    @GetMapping("/tools")
    public Result<List<Map<String, Object>>> tools(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return Result.success(retrievalAnalyticsService.tools(days));
    }

    /** 知识库维度 */
    @GetMapping("/kbs")
    public Result<List<Map<String, Object>>> kbs(
            @RequestParam(value = "days", defaultValue = "30") int days) {
        return Result.success(retrievalAnalyticsService.kbs(days));
    }

    /** 低质量查询分页 */
    @GetMapping("/low-quality")
    public Result<Map<String, Object>> lowQuality(
            @RequestParam(value = "days", defaultValue = "30") int days,
            @RequestParam(value = "current", defaultValue = "1") int current,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "type", defaultValue = "zero") String type) {
        return Result.success(retrievalAnalyticsService.lowQuality(days, type, current, size));
    }

    /** 单条问答明细（含完整 retrieval_log） */
    @GetMapping("/detail/{messageId}")
    public Result<Map<String, Object>> detail(@PathVariable Long messageId) {
        return Result.success(retrievalAnalyticsService.detail(messageId));
    }

    /** 低质量查询 CSV 导出（UTF-8 BOM，文件名 RFC 5987） */
    @GetMapping("/low-quality/export")
    public ResponseEntity<byte[]> exportLowQuality(
            @RequestParam(value = "days", defaultValue = "30") int days,
            @RequestParam(value = "type", defaultValue = "zero") String type) {
        byte[] data = retrievalAnalyticsService.exportLowQualityCsv(days, type);
        String filename = "low-quality-" + LocalDate.now().format(FILE_DATE) + ".csv";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv;charset=UTF-8"));
        headers.setContentDisposition(
                ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers).body(data);
    }
}
