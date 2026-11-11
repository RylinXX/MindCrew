package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.AuditLog;
import com.simon.MindCrew.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 审计日志服务 · 任务 12.1
 *
 * 提供两类入口：
 *   - record()         同步记录（少用，避免阻塞主流程）
 *   - recordAsync()    异步记录（主路径）
 *
 * AOP @Audited 注解触发会走 recordAsync。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogMapper auditMapper;

    @Async
    public void recordAsync(AuditLog log) {
        try {
            if (log.getCreatedAt() == null) log.setCreatedAt(LocalDateTime.now());
            auditMapper.insert(log);
        } catch (Exception e) {
            AuditLogService.log.warn("[Audit] 异步入库失败 action={}: {}", log.getAction(), e.getMessage());
        }
    }

    public void record(AuditLog log) {
        try {
            if (log.getCreatedAt() == null) log.setCreatedAt(LocalDateTime.now());
            auditMapper.insert(log);
        } catch (Exception e) {
            AuditLogService.log.warn("[Audit] 同步入库失败 action={}: {}", log.getAction(), e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 查询
    // ─────────────────────────────────────────────

    public IPage<AuditLog> page(int current, int size,
                                Long userId, String action, String targetType, String status,
                                LocalDate from, LocalDate to) {
        Page<AuditLog> p = new Page<>(current, size);
        LambdaQueryWrapper<AuditLog> w = new LambdaQueryWrapper<AuditLog>()
                .eq(userId != null, AuditLog::getUserId, userId)
                .eq(action != null && !action.isBlank(), AuditLog::getAction, action)
                .eq(targetType != null && !targetType.isBlank(), AuditLog::getTargetType, targetType)
                .eq(status != null && !status.isBlank(), AuditLog::getStatus, status)
                .ge(from != null, AuditLog::getCreatedAt, from == null ? null : from.atStartOfDay())
                .le(to   != null, AuditLog::getCreatedAt, to   == null ? null : to.plusDays(1).atStartOfDay())
                .orderByDesc(AuditLog::getCreatedAt);
        return auditMapper.selectPage(p, w);
    }
}
