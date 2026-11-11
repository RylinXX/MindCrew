package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.entity.KbAcl;
import com.simon.MindCrew.entity.KbKnowledgeBase;
import com.simon.MindCrew.entity.SysDepartment;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.mapper.KbAclMapper;
import com.simon.MindCrew.mapper.KbKnowledgeBaseMapper;
import com.simon.MindCrew.mapper.SysDepartmentMapper;
import com.simon.MindCrew.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 知识库 ACL 服务 · 任务 7 职位独立 KB 核心
 *
 * 访问规则（按优先级判定）：
 *   1. 系统管理员（role=admin） → 全部 KB 通行
 *   2. KB.visibility = public        → 所有人可读
 *   3. KB.visibility = private       → 仅 creator (user_id) 可访问
 *   4. KB.visibility = scoped        → 必须在 kb_acl 中有匹配的 position_id + 足够 permission
 *
 * 权限层级（高 → 低）：admin > write > read
 *   - read 操作需要至少 read
 *   - write 操作需要至少 write
 *   - admin 操作（删除/授权）需要 admin
 *
 * 务实约束（铁律）：
 *   - 不缓存（首版）· 命中 DB 但走索引；并发上来再加 Caffeine
 *   - 失败必须返回 false 而非抛异常（让上层决定 403 还是过滤）
 *   - 提供 listAccessibleKbIds() 让上层一次过滤（不允许 N+1 调用）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KbAclService {

    private final KbAclMapper aclMapper;
    private final KbKnowledgeBaseMapper kbMapper;
    private final SysUserMapper userMapper;
    private final SysDepartmentMapper deptMapper;

    public static final String PERM_READ  = "read";
    public static final String PERM_WRITE = "write";
    public static final String PERM_ADMIN = "admin";

    // 权限层级
    private static final Map<String, Integer> PERM_LEVEL = Map.of(
            PERM_READ,  1,
            PERM_WRITE, 2,
            PERM_ADMIN, 3
    );

    // ─────────────────────────────────────────────
    // 单点判定：用户能否对某 KB 做某事？
    // ─────────────────────────────────────────────
    public boolean canAccess(Long userId, Long kbId, String requiredPerm) {
        if (userId == null || kbId == null) return false;

        SysUser user = userMapper.selectById(userId);
        if (user == null) return false;

        // 1) 系统管理员通行
        if ("admin".equalsIgnoreCase(user.getRole())) return true;

        KbKnowledgeBase kb = kbMapper.selectById(kbId);
        if (kb == null) return false;

        String visibility = kb.getVisibility() == null ? "public" : kb.getVisibility();

        // 2) public · 所有登录用户可读；写/管理需要进一步判断
        if ("public".equals(visibility)) {
            if (PERM_READ.equals(requiredPerm)) return true;
            // write / admin 默认仅 creator
            return Objects.equals(user.getId(), kb.getUserId());
        }

        // 3) private · 仅 creator
        if ("private".equals(visibility)) {
            return Objects.equals(user.getId(), kb.getUserId());
        }

        // 4) scoped · 按 ACL · 双层判定（职位 OR 部门含祖先）
        // creator 永远是 admin 权限
        if (Objects.equals(user.getId(), kb.getUserId())) return true;

        // 查这条 KB 所有 ACL（数量有限，一次取出按内存匹配）
        List<KbAcl> acls = aclMapper.selectList(new LambdaQueryWrapper<KbAcl>()
                .eq(KbAcl::getKbId, kbId));
        if (acls.isEmpty()) return false;

        // a) 职位匹配
        if (user.getPositionId() != null) {
            for (KbAcl a : acls) {
                if (a.getPositionId() != null
                        && a.getPositionId().equals(user.getPositionId())
                        && permitsAtLeast(a.getPermission(), requiredPerm)) {
                    return true;
                }
            }
        }

        // b) 部门匹配 · 含用户所属部门的全部祖先（部门继承）
        Set<Long> userDeptChain = resolveDeptAncestors(user.getDepartmentId());
        if (!userDeptChain.isEmpty()) {
            for (KbAcl a : acls) {
                if (a.getDepartmentId() != null
                        && userDeptChain.contains(a.getDepartmentId())
                        && permitsAtLeast(a.getPermission(), requiredPerm)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * 计算某部门的祖先链（自己 + 父 + 父的父 ... 到根）
     * 用于"上级部门授权 → 下级部门自动有权"的继承语义
     */
    private Set<Long> resolveDeptAncestors(Long deptId) {
        if (deptId == null) return Collections.emptySet();
        Set<Long> chain = new LinkedHashSet<>();
        Long cur = deptId;
        int safety = 16;   // 防循环引用
        while (cur != null && chain.size() < safety) {
            chain.add(cur);
            SysDepartment d;
            try { d = deptMapper.selectById(cur); }
            catch (Exception e) { break; }
            if (d == null) break;
            cur = d.getParentId();
        }
        return chain;
    }

    private boolean permitsAtLeast(String have, String need) {
        Integer h = PERM_LEVEL.getOrDefault(have == null ? "" : have, 0);
        Integer n = PERM_LEVEL.getOrDefault(need == null ? "" : need, 1);
        return h >= n;
    }

    // ─────────────────────────────────────────────
    // 批量判定：返回用户**可读**的全部 KB ID 列表
    //
    // 用于检索 / 列表过滤的核心入口。
    // 必须高效，因为每次 chat / list 都要调用。
    // ─────────────────────────────────────────────
    public List<Long> listAccessibleKbIds(Long userId) {
        if (userId == null) return Collections.emptyList();
        SysUser user = userMapper.selectById(userId);
        if (user == null) return Collections.emptyList();

        // admin · 全部
        if ("admin".equalsIgnoreCase(user.getRole())) {
            return kbMapper.selectList(new LambdaQueryWrapper<KbKnowledgeBase>()
                            .select(KbKnowledgeBase::getId))
                    .stream().map(KbKnowledgeBase::getId).toList();
        }

        // 普通用户 · public + 自己创建 + scoped 中 ACL 命中
        List<KbKnowledgeBase> all = kbMapper.selectList(
                new LambdaQueryWrapper<KbKnowledgeBase>()
                        .select(KbKnowledgeBase::getId, KbKnowledgeBase::getVisibility, KbKnowledgeBase::getUserId)
        );

        // 一次性算出该用户可访问的 KB 集合（避免 N+1）
        // 包括两路：职位级 ACL + 部门级 ACL（含部门祖先链）
        Set<Long> scopedKbIds = new HashSet<>();
        Set<Long> deptChain = resolveDeptAncestors(user.getDepartmentId());

        if (user.getPositionId() != null || !deptChain.isEmpty()) {
            LambdaQueryWrapper<KbAcl> w = new LambdaQueryWrapper<KbAcl>()
                    .and(q -> {
                        boolean has = false;
                        if (user.getPositionId() != null) {
                            q.eq(KbAcl::getPositionId, user.getPositionId());
                            has = true;
                        }
                        if (!deptChain.isEmpty()) {
                            if (has) q.or();
                            q.in(KbAcl::getDepartmentId, deptChain);
                        }
                    });
            List<KbAcl> acls = aclMapper.selectList(w);
            for (KbAcl a : acls) {
                if (permitsAtLeast(a.getPermission(), PERM_READ)) {
                    scopedKbIds.add(a.getKbId());
                }
            }
        }

        List<Long> result = new ArrayList<>();
        for (KbKnowledgeBase kb : all) {
            String vis = kb.getVisibility() == null ? "public" : kb.getVisibility();
            if ("public".equals(vis)) {
                result.add(kb.getId());
            } else if (Objects.equals(kb.getUserId(), user.getId())) {
                result.add(kb.getId());     // 自己创建的
            } else if ("scoped".equals(vis) && scopedKbIds.contains(kb.getId())) {
                result.add(kb.getId());
            }
            // private 且非自己创建 → 跳过
        }
        return result;
    }

    // ─────────────────────────────────────────────
    // ACL 写入：给 KB 授权（职位级或部门级）
    // ─────────────────────────────────────────────
    @Transactional(rollbackFor = Exception.class)
    @com.simon.MindCrew.common.audit.Audited(action = "kb.acl.grant", label = "KB 授权", targetType = "kb", targetIdParam = "$arg0")
    public void grant(Long kbId, Long positionId, Long departmentId, String permission, Long grantedBy) {
        if (!PERM_LEVEL.containsKey(permission)) {
            throw new IllegalArgumentException("非法 permission: " + permission);
        }
        // 业务约束：position_id 与 department_id 必须二选一
        boolean hasPos = positionId != null;
        boolean hasDept = departmentId != null;
        if (hasPos == hasDept) {  // 都为空 或 都非空
            throw new IllegalArgumentException("必须指定 positionId 或 departmentId 二选一");
        }

        // 幂等：相同 subject 已存在 → 更新；否则插入
        LambdaQueryWrapper<KbAcl> w = new LambdaQueryWrapper<KbAcl>().eq(KbAcl::getKbId, kbId);
        if (hasPos)  w.eq(KbAcl::getPositionId,   positionId);
        else         w.eq(KbAcl::getDepartmentId, departmentId);
        KbAcl existing = aclMapper.selectOne(w.last("LIMIT 1"));
        if (existing != null) {
            existing.setPermission(permission);
            existing.setGrantedBy(grantedBy);
            aclMapper.updateById(existing);
        } else {
            KbAcl acl = new KbAcl();
            acl.setKbId(kbId);
            acl.setPositionId(positionId);
            acl.setDepartmentId(departmentId);
            acl.setPermission(permission);
            acl.setGrantedBy(grantedBy);
            aclMapper.insert(acl);
        }
        log.info("[Acl] grant kb={} {}={} perm={} by={}",
                kbId, hasPos ? "position" : "department",
                hasPos ? positionId : departmentId, permission, grantedBy);
    }

    /** 撤销某 subject 对 KB 的访问 · subject 用 positionId 或 departmentId 标识 */
    public void revoke(Long kbId, Long positionId, Long departmentId) {
        LambdaQueryWrapper<KbAcl> w = new LambdaQueryWrapper<KbAcl>().eq(KbAcl::getKbId, kbId);
        if (positionId != null)        w.eq(KbAcl::getPositionId,   positionId);
        else if (departmentId != null) w.eq(KbAcl::getDepartmentId, departmentId);
        else throw new IllegalArgumentException("positionId 或 departmentId 必传一个");
        aclMapper.delete(w);
        log.info("[Acl] revoke kb={} pos={} dept={}", kbId, positionId, departmentId);
    }

    /** 列出 KB 当前所有授权 */
    public List<KbAcl> listAclByKb(Long kbId) {
        return aclMapper.selectList(new LambdaQueryWrapper<KbAcl>()
                .eq(KbAcl::getKbId, kbId)
                .orderByAsc(KbAcl::getDepartmentId)
                .orderByAsc(KbAcl::getPositionId));
    }

    /** 一次性替换 KB 的所有 ACL（前端整体保存） · 支持混合职位+部门 */
    @Transactional(rollbackFor = Exception.class)
    public void replaceAcls(Long kbId, List<KbAclEntry> entries, Long grantedBy) {
        aclMapper.delete(new LambdaQueryWrapper<KbAcl>().eq(KbAcl::getKbId, kbId));
        if (entries == null) return;
        for (KbAclEntry e : entries) {
            if (!PERM_LEVEL.containsKey(e.permission)) continue;
            // 业务约束：二选一
            boolean hasPos = e.positionId != null;
            boolean hasDept = e.departmentId != null;
            if (hasPos == hasDept) continue;   // 都空或都非空都跳过
            KbAcl acl = new KbAcl();
            acl.setKbId(kbId);
            acl.setPositionId(e.positionId);
            acl.setDepartmentId(e.departmentId);
            acl.setPermission(e.permission);
            acl.setGrantedBy(grantedBy);
            aclMapper.insert(acl);
        }
        log.info("[Acl] replaced kb={} entries={}", kbId, entries.size());
    }

    /** ACL 条目 · positionId / departmentId 二选一 */
    public record KbAclEntry(Long positionId, Long departmentId, String permission) {}
}
