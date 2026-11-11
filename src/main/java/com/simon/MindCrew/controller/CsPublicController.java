package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.audit.Audited;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.CsSession;
import com.simon.MindCrew.service.CsChatService;
import com.simon.MindCrew.service.CsConfigService;
import com.simon.MindCrew.service.CsSessionService;
import com.simon.MindCrew.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客服中心 · 公开接口（无需登录，白名单）
 *
 * 路径：/api/cs/public/**
 * 鉴权：除 GET /config 与 POST /session 外，全部需要请求头 X-CS-Token（访客 token）
 * 契约：docs/design-cs-center.md 4.1
 */
@RestController
@RequestMapping("/api/cs/public")
@RequiredArgsConstructor
public class CsPublicController {

    private final CsConfigService configService;
    private final CsSessionService sessionService;
    private final CsChatService chatService;
    private final UserService userService;

    /** 对外页面配置 */
    @GetMapping("/config")
    public Result<Map<String, Object>> config() {
        return Result.success(configService.publicView());
    }

    /** 新建访客会话（返回 sessionNo + visitorToken；SSO 登录后可选绑定本地用户） */
    @PostMapping("/session")
    public Result<Map<String, Object>> createSession(@RequestBody(required = false) CreateSessionReq req,
                                                     HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        String userAgent = http.getHeader("User-Agent");
        Long ssoUserId = currentUserIdOrNull();
        return Result.success(sessionService.createVisitorSession(
                req == null ? null : req.getVisitorKey(),
                req == null ? null : req.getVisitorName(),
                req == null ? null : req.getChannel(),
                null, ssoUserId, ip, userAgent));
    }

    /**
     * 可选识别 JWT（SSO 后前端会带 Authorization）。
     * 白名单接口没有强制登录态，识别不到返回 null，绝不抛异常。
     */
    private Long currentUserIdOrNull() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null || !authentication.isAuthenticated()) return null;
            if (authentication instanceof AnonymousAuthenticationToken) return null;
            return userService.getCurrentUserId();
        } catch (Exception e) {
            return null;
        }
    }

    /** 轮询新消息（sinceId 之后） */
    @GetMapping("/messages")
    public Result<Map<String, Object>> messages(
            @RequestParam(required = false) String sessionNo,
            @RequestParam(defaultValue = "0") Long sinceId,
            @RequestHeader(value = "X-CS-Token", required = false) String token) {
        sessionService.verifyVisitor(token, sessionNo);
        return Result.success(sessionService.pollMessages(sessionNo, sinceId));
    }

    /** 访客发消息 · 立即返回 pending，AI 异步回答 */
    @PostMapping("/message")
    public Result<Map<String, Object>> sendMessage(@RequestBody(required = false) MessageReq req,
                                                   @RequestHeader(value = "X-CS-Token", required = false) String token) {
        if (req == null) throw new BusinessException(400, "参数不能为空");
        sessionService.verifyVisitor(token, req.getSessionNo());
        return Result.success(chatService.sendMessage(req.getSessionNo(), req.getContent()));
    }

    /** 主动转人工 */
    @PostMapping("/handoff")
    @Audited(action = "cs.visitor.handoff", label = "访客转人工", targetType = "cs_session")
    public Result<Map<String, Object>> handoff(@RequestBody(required = false) SessionRefReq req,
                                               @RequestHeader(value = "X-CS-Token", required = false) String token) {
        if (req == null) throw new BusinessException(400, "参数不能为空");
        CsSession session = sessionService.verifyVisitor(token, req.getSessionNo());
        sessionService.handoff(session.getId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", CsSessionService.STATUS_HUMAN);
        return Result.success(body);
    }

    /** 满意度评分 */
    @PostMapping("/rating")
    @Audited(action = "cs.visitor.rating", label = "访客满意度评分", targetType = "cs_session")
    public Result<Map<String, Object>> rating(@RequestBody(required = false) RatingReq req,
                                              @RequestHeader(value = "X-CS-Token", required = false) String token) {
        if (req == null) throw new BusinessException(400, "参数不能为空");
        CsSession session = sessionService.verifyVisitor(token, req.getSessionNo());
        Integer rating = sessionService.rate(session.getId(), req.getRating(), req.getNote());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rating", rating);
        return Result.success(body);
    }

    /** 访客关闭会话 */
    @PostMapping("/close")
    @Audited(action = "cs.visitor.close", label = "访客关闭会话", targetType = "cs_session")
    public Result<Map<String, Object>> close(@RequestBody(required = false) SessionRefReq req,
                                             @RequestHeader(value = "X-CS-Token", required = false) String token) {
        if (req == null) throw new BusinessException(400, "参数不能为空");
        CsSession session = sessionService.verifyVisitor(token, req.getSessionNo());
        sessionService.close(session.getId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", CsSessionService.STATUS_CLOSED);
        return Result.success(body);
    }

    // ───────── DTO ─────────

    @Data
    public static class CreateSessionReq {
        private String visitorKey;
        private String visitorName;
        private String channel;
    }

    @Data
    public static class MessageReq {
        private String sessionNo;
        private String content;
    }

    @Data
    public static class SessionRefReq {
        private String sessionNo;
    }

    @Data
    public static class RatingReq {
        private String sessionNo;
        private Integer rating;
        private String note;
    }
}
