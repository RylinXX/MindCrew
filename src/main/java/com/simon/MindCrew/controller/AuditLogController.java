package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.AuditLog;
import com.simon.MindCrew.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 审计日志查询 API · 任务 12.3
 *   GET    /api/v2/audit/page         分页查
 *   GET    /api/v2/audit/export.csv   导出 CSV（合规需要）
 *   GET    /api/v2/audit/action-codes 已记录的动作 code 列表（下拉用）
 */
@RestController
@RequestMapping("/api/v2/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<IPage<AuditLog>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return Result.success(auditLogService.page(current, size, userId, action, targetType, status, from, to));
    }

    /** 导出 CSV · 默认导出最近 30 天，最多 5000 行 */
    @GetMapping("/export.csv")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public ResponseEntity<byte[]> exportCsv(
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from == null) from = LocalDate.now().minusDays(30);
        if (to == null)   to = LocalDate.now();

        IPage<AuditLog> page = auditLogService.page(1, 5000, userId, action, targetType, status, from, to);
        List<AuditLog> logs = page.getRecords();

        StringBuilder sb = new StringBuilder();
        sb.append("time,user_id,username,action,label,target_type,target_id,target_name,status,ip,latency_ms,error_msg\n");
        DateTimeFormatter dt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (AuditLog l : logs) {
            sb.append(l.getCreatedAt() == null ? "" : l.getCreatedAt().format(dt)).append(',')
              .append(l.getUserId() == null ? "" : l.getUserId()).append(',')
              .append(csvEscape(l.getUsername())).append(',')
              .append(csvEscape(l.getAction())).append(',')
              .append(csvEscape(l.getActionLabel())).append(',')
              .append(csvEscape(l.getTargetType())).append(',')
              .append(csvEscape(l.getTargetId())).append(',')
              .append(csvEscape(l.getTargetName())).append(',')
              .append(csvEscape(l.getStatus())).append(',')
              .append(csvEscape(l.getIp())).append(',')
              .append(l.getLatencyMs() == null ? "" : l.getLatencyMs()).append(',')
              .append(csvEscape(l.getErrorMsg())).append('\n');
        }
        // BOM 让 Excel 正确识别 UTF-8
        byte[] data = ("﻿" + sb).getBytes(StandardCharsets.UTF_8);
        String filename = "audit-log-" + from + "_" + to + ".csv";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("text/csv;charset=utf-8"))
                .body(data);
    }

    private static String csvEscape(String s) {
        if (s == null) return "";
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0;
        String escaped = s.replace("\"", "\"\"");
        return needQuote ? "\"" + escaped + "\"" : escaped;
    }
}
