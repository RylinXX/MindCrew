package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.audit.Audited;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.service.ConversationSearchService;
import com.simon.MindCrew.service.ConversationSearchService.ConvDetailVO;
import com.simon.MindCrew.service.ConversationSearchService.SearchRequest;
import com.simon.MindCrew.service.ConversationSearchService.SearchResult;
import com.simon.MindCrew.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 历史对话搜索 API · 任务 13.5
 *
 *   GET    /api/v2/admin/conversation/search        · 搜索
 *   GET    /api/v2/admin/conversation/{id}/detail   · 完整对话
 *   POST   /api/v2/admin/conversation/{id}/flag     · 标记敏感
 *   DELETE /api/v2/admin/conversation/{id}/flag     · 取消标记
 *
 * 鉴权策略：
 *   - 搜索 / 详情：登录用户即可（service 内做 ACL 分级，普通用户仅能看自己）
 *   - 标记 / 取消：service 内强制 admin/auditor
 */
@RestController
@RequestMapping("/api/v2/admin/conversation")
@RequiredArgsConstructor
public class AdminConversationController {

    private final ConversationSearchService searchService;
    private final UserService userService;

    @GetMapping("/search")
    public Result<SearchResult> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) Long deptId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false) Boolean onlyFlagged,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size) {
        SysUser viewer = userService.getCurrentUser();
        SearchRequest req = new SearchRequest();
        req.setKeyword(keyword);
        req.setUserId(userId);
        req.setDeptId(deptId);
        req.setFrom(from);
        req.setTo(to);
        req.setOnlyFlagged(onlyFlagged);
        req.setCurrent(current);
        req.setSize(size);
        return Result.success(searchService.search(req, viewer));
    }

    @GetMapping("/{id}/detail")
    public Result<ConvDetailVO> detail(@PathVariable Long id) {
        SysUser viewer = userService.getCurrentUser();
        return Result.success(searchService.detail(id, viewer));
    }

    @PostMapping("/{id}/flag")
    @Audited(action = "conversation.flag", label = "标记敏感对话",
             targetType = "conversation", targetIdParam = "id")
    public Result<Void> flag(@PathVariable Long id, @RequestBody FlagDTO dto) {
        SysUser operator = userService.getCurrentUser();
        searchService.flag(id, dto == null ? null : dto.getNote(), operator);
        return Result.success();
    }

    @DeleteMapping("/{id}/flag")
    @Audited(action = "conversation.unflag", label = "取消敏感标记",
             targetType = "conversation", targetIdParam = "id")
    public Result<Void> unflag(@PathVariable Long id) {
        SysUser operator = userService.getCurrentUser();
        searchService.unflag(id, operator);
        return Result.success();
    }

    @Data
    public static class FlagDTO {
        private String note;
    }
}
