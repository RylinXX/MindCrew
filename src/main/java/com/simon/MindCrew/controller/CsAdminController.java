package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.audit.Audited;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.CsConfig;
import com.simon.MindCrew.entity.CsDingTalkChannel;
import com.simon.MindCrew.entity.CsSsoApp;
import com.simon.MindCrew.service.CsConfigService;
import com.simon.MindCrew.service.CsDingTalkService;
import com.simon.MindCrew.service.CsSessionService;
import com.simon.MindCrew.service.CsSsoService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服中心 · 管理接口（仅 ADMIN）
 *
 * 路径：/api/cs/admin/**
 * 契约：docs/design-cs-center.md 4.4
 */
@RestController
@RequestMapping("/api/cs/admin")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class CsAdminController {

    private final CsConfigService configService;
    private final CsSsoService ssoService;
    private final CsDingTalkService dingTalkService;
    private final CsSessionService sessionService;

    // ─────────────────────────────────────────────
    // 页面配置
    // ─────────────────────────────────────────────

    @GetMapping("/config")
    public Result<CsConfig> config() {
        return Result.success(configService.getFull());
    }

    @PutMapping("/config")
    @Audited(action = "cs.config.update", label = "保存客服中心配置", targetType = "cs_config")
    public Result<CsConfig> saveConfig(@RequestBody(required = false) CsConfig dto) {
        if (dto == null) throw new BusinessException(400, "参数不能为空");
        return Result.success(configService.save(dto));
    }

    // ─────────────────────────────────────────────
    // SSO 应用
    // ─────────────────────────────────────────────

    /** 列表 · secret 打码 */
    @GetMapping("/sso-apps")
    public Result<List<CsSsoApp>> ssoApps() {
        return Result.success(ssoService.listApps());
    }

    /**
     * 新建 · 返回一次明文 appSecret（data.appSecret）
     * 注意：不挂 @Audited —— 返回值含一次性明文密钥，落审计日志会泄漏密钥。
     */
    @PostMapping("/sso-apps")
    public Result<Map<String, Object>> createSsoApp(@RequestBody(required = false) SsoAppReq req) {
        if (req == null) throw new BusinessException(400, "参数不能为空");
        return Result.success(ssoService.createApp(req.getAppName(), req.getRemark(), req.getDefaultRole(),
                req.getAutoCreateUser(), req.getEnabled()));
    }

    /** 更新（recordArgs=false：入参可能含新明文 secret，不落审计明细） */
    @PutMapping("/sso-apps/{id}")
    @Audited(action = "cs.sso_app.update", label = "更新 SSO 应用", targetType = "cs_sso_app",
            targetIdParam = "$arg0", recordArgs = false)
    public Result<CsSsoApp> updateSsoApp(@PathVariable Long id, @RequestBody(required = false) CsSsoApp dto) {
        if (dto == null) throw new BusinessException(400, "参数不能为空");
        return Result.success(ssoService.updateApp(id, dto));
    }

    /** 逻辑删除 */
    @DeleteMapping("/sso-apps/{id}")
    @Audited(action = "cs.sso_app.delete", label = "删除 SSO 应用", targetType = "cs_sso_app", targetIdParam = "$arg0")
    public Result<Void> deleteSsoApp(@PathVariable Long id) {
        ssoService.deleteApp(id);
        return Result.success();
    }

    /**
     * 重置密钥 · 返回一次明文（data.appSecret）
     * 注意：不挂 @Audited —— 返回值含一次性明文密钥，落审计日志会泄漏密钥。
     */
    @PostMapping("/sso-apps/{id}/rotate-secret")
    public Result<Map<String, Object>> rotateSecret(@PathVariable Long id) {
        return Result.success(ssoService.rotateSecret(id));
    }

    // ─────────────────────────────────────────────
    // 钉钉渠道
    // ─────────────────────────────────────────────

    @GetMapping("/channels/dingtalk")
    public Result<CsDingTalkChannel> dingTalk() {
        return Result.success(dingTalkService.getForAdmin());
    }

    /** 保存钉钉配置（recordArgs=false：入参可能含新明文 secret） */
    @PutMapping("/channels/dingtalk")
    @Audited(action = "cs.dingtalk.update", label = "保存钉钉渠道配置",
            targetType = "cs_dingtalk_channel", recordArgs = false)
    public Result<CsDingTalkChannel> saveDingTalk(@RequestBody(required = false) CsDingTalkChannel dto) {
        if (dto == null) throw new BusinessException(400, "参数不能为空");
        return Result.success(dingTalkService.save(dto));
    }

    /** 走 RAG 生成回复，仅返回文本，不真发 */
    @PostMapping("/channels/dingtalk/test")
    public Result<String> testDingTalk(@RequestBody(required = false) DingTalkTestReq req) {
        if (req == null) throw new BusinessException(400, "参数不能为空");
        Map<String, Object> result = dingTalkService.test(req.getContent());
        Object answer = result.get("answer");
        return Result.success(answer == null ? "" : String.valueOf(answer));
    }

    // ─────────────────────────────────────────────
    // 概览
    // ─────────────────────────────────────────────

    @GetMapping("/overview")
    public Result<Map<String, Object>> overview() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("config", configService.getFull());
        body.put("ssoAppCount", ssoService.countApps());
        body.put("sessionTotal", sessionService.countTotal());
        body.put("todaySessions", sessionService.countToday());
        return Result.success(body);
    }

    // ───────── DTO ─────────

    @Data
    public static class SsoAppReq {
        private String appName;
        private String remark;
        private String defaultRole;
        private Integer autoCreateUser;
        private Integer enabled;
    }

    @Data
    public static class DingTalkTestReq {
        private String content;
    }
}
