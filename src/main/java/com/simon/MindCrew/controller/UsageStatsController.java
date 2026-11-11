package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.UsageDaily;
import com.simon.MindCrew.service.UsageStatsService;
import com.simon.MindCrew.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 用量统计 · 任务 13.6 管理员逐用户钻取
 *
 *   GET /api/v2/usage/me                       · 当前用户本月用量
 *   GET /api/v2/usage/user/{id}/summary        · 管理员看单用户区间汇总
 *   GET /api/v2/usage/user/{id}/days           · 管理员看单用户每日明细
 *   GET /api/v2/usage/top-users                · 管理员看本月成本 Top N
 */
@RestController
@RequestMapping("/api/v2/usage")
@RequiredArgsConstructor
public class UsageStatsController {

    private final UsageStatsService usageStatsService;
    private final UserService userService;

    /** 当前登录用户的本月用量 */
    @GetMapping("/me")
    public Result<Map<String, Object>> me() {
        Long uid = userService.getCurrentUserId();
        LocalDate firstDay = LocalDate.now().withDayOfMonth(1);
        return Result.success(usageStatsService.summarizeUser(uid, firstDay, LocalDate.now()));
    }

    @GetMapping("/user/{id}/summary")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<Map<String, Object>> userSummary(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from == null) from = LocalDate.now().minusDays(30);
        if (to   == null) to = LocalDate.now();
        return Result.success(usageStatsService.summarizeUser(id, from, to));
    }

    @GetMapping("/user/{id}/days")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<List<UsageDaily>> userDays(
            @PathVariable Long id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from == null) from = LocalDate.now().minusDays(30);
        if (to   == null) to = LocalDate.now();
        return Result.success(usageStatsService.listUserUsage(id, from, to));
    }

    @GetMapping("/top-users")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<List<UsageDaily>> topUsers(@RequestParam(defaultValue = "20") int topN) {
        return Result.success(usageStatsService.topUsersThisMonth(topN));
    }
}
