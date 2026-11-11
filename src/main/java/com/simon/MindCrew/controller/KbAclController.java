package com.simon.MindCrew.controller;

import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.KbAcl;
import com.simon.MindCrew.service.KbAclService;
import com.simon.MindCrew.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * KB 访问控制 API
 *
 *   GET   /api/v2/kb-acl/{kbId}            列出某 KB 的所有授权
 *   POST  /api/v2/kb-acl/{kbId}/grant      授权一个职位
 *   DELETE /api/v2/kb-acl/{kbId}/revoke    撤销一个职位
 *   PUT   /api/v2/kb-acl/{kbId}/replace    一次性替换 KB 的所有 ACL（前端拖选保存）
 *   GET   /api/v2/kb-acl/check?kbId=&perm= 校验当前用户是否有某权限（前端按钮置灰用）
 *   GET   /api/v2/kb-acl/accessible-kbs    当前用户可读 KB ID 列表
 */
@RestController
@RequestMapping("/api/v2/kb-acl")
@RequiredArgsConstructor
public class KbAclController {

    private final KbAclService aclService;
    private final UserService userService;

    @GetMapping("/{kbId}")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<List<KbAcl>> list(@PathVariable Long kbId) {
        return Result.success(aclService.listAclByKb(kbId));
    }

    @PostMapping("/{kbId}/grant")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> grant(@PathVariable Long kbId, @RequestBody GrantDTO dto) {
        Long me = userService.getCurrentUserId();
        aclService.grant(kbId, dto.getPositionId(), dto.getDepartmentId(), dto.getPermission(), me);
        return Result.success();
    }

    @DeleteMapping("/{kbId}/revoke")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> revoke(@PathVariable Long kbId,
                               @RequestParam(required = false) Long positionId,
                               @RequestParam(required = false) Long departmentId) {
        aclService.revoke(kbId, positionId, departmentId);
        return Result.success();
    }

    @PutMapping("/{kbId}/replace")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> replace(@PathVariable Long kbId, @RequestBody ReplaceDTO dto) {
        Long me = userService.getCurrentUserId();
        aclService.replaceAcls(kbId, dto.getEntries(), me);
        return Result.success();
    }

    @GetMapping("/check")
    public Result<Boolean> check(@RequestParam Long kbId,
                                 @RequestParam(defaultValue = "read") String perm) {
        Long me = userService.getCurrentUserId();
        return Result.success(aclService.canAccess(me, kbId, perm));
    }

    @GetMapping("/accessible-kbs")
    public Result<List<Long>> accessibleKbs() {
        Long me = userService.getCurrentUserId();
        return Result.success(aclService.listAccessibleKbIds(me));
    }

    @Data
    public static class GrantDTO {
        private Long positionId;
        private Long departmentId;
        private String permission;
    }

    @Data
    public static class ReplaceDTO {
        private List<KbAclService.KbAclEntry> entries;
    }
}
