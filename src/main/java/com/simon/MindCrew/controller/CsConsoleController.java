package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.audit.Audited;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.CsMessage;
import com.simon.MindCrew.entity.CsSession;
import com.simon.MindCrew.service.CsSessionService;
import com.simon.MindCrew.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 客服中心 · 工作台接口（登录即可）
 *
 * 路径：/api/cs/console/**
 * 契约：docs/design-cs-center.md 4.3
 */
@RestController
@RequestMapping("/api/cs/console")
@RequiredArgsConstructor
public class CsConsoleController {

    private final CsSessionService sessionService;
    private final UserService userService;

    /** 会话列表（分页）· records 内补 assigneeName */
    @GetMapping("/sessions")
    public Result<Page<CsSession>> sessions(
            @RequestParam(defaultValue = "1") Integer current,
            @RequestParam(defaultValue = "10") Integer size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String keyword) {
        return Result.success(sessionService.pageSessions(current, size, status, channel, keyword));
    }

    /** 会话详情：{session, assigneeName, messages[]} */
    @GetMapping("/sessions/{id}")
    public Result<Map<String, Object>> detail(@PathVariable Long id) {
        return Result.success(sessionService.detail(id));
    }

    /** 认领：status→human，assignee=当前用户 */
    @PostMapping("/sessions/{id}/claim")
    @Audited(action = "cs.session.claim", label = "认领客服会话", targetType = "cs_session", targetIdParam = "$arg0")
    public Result<CsSession> claim(@PathVariable Long id) {
        Long userId = userService.getCurrentUserId();
        return Result.success(sessionService.claim(id, userId));
    }

    /** 人工回复 · 写 role=agent 消息 */
    @PostMapping("/sessions/{id}/reply")
    @Audited(action = "cs.session.reply", label = "客服人工回复", targetType = "cs_session", targetIdParam = "$arg0")
    public Result<CsMessage> reply(@PathVariable Long id, @RequestBody(required = false) ReplyReq req) {
        if (req == null) throw new BusinessException(400, "参数不能为空");
        Long userId = userService.getCurrentUserId();
        return Result.success(sessionService.reply(id, userId, req.getContent()));
    }

    /** 转接坐席 */
    @PostMapping("/sessions/{id}/transfer")
    @Audited(action = "cs.session.transfer", label = "转接客服会话", targetType = "cs_session", targetIdParam = "$arg0")
    public Result<CsSession> transfer(@PathVariable Long id, @RequestBody(required = false) TransferReq req) {
        if (req == null) throw new BusinessException(400, "参数不能为空");
        return Result.success(sessionService.transfer(id, req.getAssigneeId()));
    }

    /** 关闭会话 */
    @PostMapping("/sessions/{id}/close")
    @Audited(action = "cs.session.close", label = "关闭客服会话", targetType = "cs_session", targetIdParam = "$arg0")
    public Result<CsSession> close(@PathVariable Long id) {
        return Result.success(sessionService.close(id));
    }

    /** 统计（默认近 7 天） */
    @GetMapping("/stats")
    public Result<Map<String, Object>> stats(@RequestParam(defaultValue = "7") Integer days) {
        return Result.success(sessionService.stats(days));
    }

    // ───────── DTO ─────────

    @Data
    public static class ReplyReq {
        private String content;
    }

    @Data
    public static class TransferReq {
        private Long assigneeId;
    }
}
