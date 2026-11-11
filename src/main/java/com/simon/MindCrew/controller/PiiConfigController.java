package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.PiiConfig;
import com.simon.MindCrew.service.PiiMaskService;
import com.simon.MindCrew.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * PII 脱敏配置 API · 任务 12.2
 *
 *   GET  /api/v2/pii/config        当前配置
 *   PUT  /api/v2/pii/config        更新配置（admin only）
 *   POST /api/v2/pii/test          测试脱敏效果 · 输入文本 → 返回脱敏后文本
 */
@RestController
@RequestMapping("/api/v2/pii")
@RequiredArgsConstructor
public class PiiConfigController {

    private final PiiMaskService piiMaskService;
    private final UserService userService;

    @GetMapping("/config")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<PiiConfig> getConfig() {
        return Result.success(piiMaskService.getConfig());
    }

    @PutMapping("/config")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> updateConfig(@RequestBody PiiConfig patch) {
        Long me = userService.getCurrentUserId();
        piiMaskService.updateConfig(patch, me);
        return Result.success();
    }

    @PostMapping("/test")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<Map<String, String>> test(@RequestBody TestDTO dto) {
        if (dto.getText() == null) {
            return Result.success(Map.of("input", "", "output", ""));
        }
        return Result.success(Map.of(
                "input",  dto.getText(),
                "output", piiMaskService.mask(dto.getText())
        ));
    }

    @Data
    public static class TestDTO {
        private String text;
    }
}
