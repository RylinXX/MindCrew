package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.service.CsSsoService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.Map;

/**
 * 客服中心 · SSO 接口（白名单，自行验签）
 *
 * 路径：/api/cs/sso/**
 * 契约：docs/design-cs-center.md 3.3 / 4.2
 */
@RestController
@RequestMapping("/api/cs/sso")
@RequiredArgsConstructor
public class CsSsoController {

    private final CsSsoService ssoService;

    /**
     * 单点登录入口（302）
     * GET /api/cs/sso/entry?appId&userId&name&ts&nonce&sign&redirect=/cs
     */
    @GetMapping("/entry")
    public ResponseEntity<Void> entry(@RequestParam(required = false) String appId,
                                      @RequestParam(required = false) String userId,
                                      @RequestParam(required = false) String name,
                                      @RequestParam(required = false) String ts,
                                      @RequestParam(required = false) String nonce,
                                      @RequestParam(required = false) String sign,
                                      @RequestParam(required = false) String redirect) {
        String target = ssoService.buildEntryRedirect(appId, userId, name, ts, nonce, sign, redirect);
        URI location;
        try {
            location = URI.create(target);
        } catch (Exception e) {
            location = URI.create("/cs?cs_error=invalid_sign");
        }
        return ResponseEntity.status(HttpStatus.FOUND).location(location).build();
    }

    /** 一次性 ticket 换登录态 */
    @PostMapping("/exchange")
    public Result<Map<String, Object>> exchange(@RequestBody(required = false) ExchangeReq req) {
        return Result.success(ssoService.exchange(req == null ? null : req.getTicket()));
    }

    /** 对接方自检 */
    @GetMapping("/meta")
    public Result<Map<String, Object>> meta(@RequestParam(required = false) String appId) {
        return Result.success(ssoService.meta(appId));
    }

    @Data
    public static class ExchangeReq {
        private String ticket;
    }
}
