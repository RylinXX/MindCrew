package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.ApiCallLog;
import com.simon.MindCrew.entity.ApiKey;
import com.simon.MindCrew.service.ApiKeyService;
import com.simon.MindCrew.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理后台 · API Key 管理 API
 *   POST   /api/v2/api-key                     生成 key（rawKey 仅此一次返回）
 *   GET    /api/v2/api-key/page                列表
 *   GET    /api/v2/api-key/by-kb/{kbId}        某 KB 的所有 key（11.6 用）
 *   POST   /api/v2/api-key/{id}/revoke         吊销
 *   PUT    /api/v2/api-key/{id}/quota          改月配额
 *   PUT    /api/v2/api-key/{id}/kbs            改授权 KB
 *   DELETE /api/v2/api-key/{id}                删除
 *   GET    /api/v2/api-key/logs                调用日志
 */
@RestController
@RequestMapping("/api/v2/api-key")
@RequiredArgsConstructor
public class ApiKeyController {

    private final ApiKeyService apiKeyService;
    private final UserService userService;

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Map<String, Object>> issue(@RequestBody IssueDTO dto) {
        Long me = userService.getCurrentUserId();
        ApiKeyService.IssueResult r = apiKeyService.issue(
                dto.getName(),
                dto.getAllowedKbIds(),
                dto.getMonthlyQuota(),
                dto.getRateLimitQps(),
                dto.getExpireAt(),
                dto.getDescription(),
                me
        );
        // rawKey 仅此一次返回！
        return Result.success(Map.of(
                "id", r.id(),
                "rawKey", r.rawKey(),
                "prefix", r.prefix(),
                "warning", "请妥善保存 rawKey · 关闭弹窗后无法再查看完整 key，仅能看前缀"
        ));
    }

    @GetMapping("/page")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<IPage<ApiKey>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long kbId,
            @RequestParam(required = false) String status) {
        return Result.success(apiKeyService.page(current, size, null, kbId, status));
    }

    @GetMapping("/by-kb/{kbId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<ApiKey>> byKb(@PathVariable Long kbId) {
        return Result.success(apiKeyService.listByKb(kbId));
    }

    @PostMapping("/{id}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> revoke(@PathVariable Long id) {
        apiKeyService.revoke(id);
        return Result.success();
    }

    @PutMapping("/{id}/quota")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateQuota(@PathVariable Long id, @RequestBody QuotaDTO dto) {
        apiKeyService.updateQuota(id, dto.getMonthlyQuota(), dto.getRateLimitQps());
        return Result.success();
    }

    @PutMapping("/{id}/kbs")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateAllowedKbs(@PathVariable Long id, @RequestBody KbsDTO dto) {
        apiKeyService.updateAllowedKbs(id, dto.getAllowedKbIds());
        return Result.success();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        apiKeyService.delete(id);
        return Result.success();
    }

    @GetMapping("/logs")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<IPage<ApiCallLog>> logs(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Long keyId,
            @RequestParam(required = false) Long kbId) {
        return Result.success(apiKeyService.pageLogs(current, size, keyId, kbId));
    }

    // ── DTO ──
    @Data
    public static class IssueDTO {
        private String name;
        private List<Long> allowedKbIds;
        private Integer monthlyQuota;
        private Integer rateLimitQps;
        private LocalDateTime expireAt;
        private String description;
    }
    @Data
    public static class QuotaDTO {
        private Integer monthlyQuota;
        private Integer rateLimitQps;
    }
    @Data
    public static class KbsDTO {
        private List<Long> allowedKbIds;
    }
}
