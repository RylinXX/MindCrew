package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.QaConversation;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.entity.SysDepartment;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.mapper.QaConversationMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.mapper.SysDepartmentMapper;
import com.simon.MindCrew.mapper.SysUserMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 历史对话搜索服务 · 任务 13.5
 *
 * 能力：
 *   1. 跨用户搜索：admin/auditor 全部可见；其他用户仅本人
 *   2. 关键词在 qa_message.content 模糊匹配，反查 conv_id
 *   3. 按用户、部门（含子部门）、时间范围、是否敏感筛选
 *   4. 详情：完整对话历史 + sources + retrievalLog
 *   5. 主管/管理员可对会话打"敏感"标记，附备注
 *
 * 务实约束：
 *   - 没有 KB-level ACL（一旦能看到该用户就能看其所有对话）
 *   - 关键词搜索用 LIKE（兼容性优于全文，全文索引在迁移里也建了，后续可优化为 MATCH）
 *   - 大查询场景：关键词反查 conv_ids 限 1000，避免 IN 过长
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSearchService {

    private final QaConversationMapper convMapper;
    private final QaMessageMapper messageMapper;
    private final SysUserMapper userMapper;
    private final SysDepartmentMapper deptMapper;

    private static final int MAX_KEYWORD_MATCH_CONV = 1000;
    private static final int SNIPPET_CONTEXT = 30;     // 关键词前后各 30 字
    private static final int MAX_SNIPPETS_PER_CONV = 3;

    // ─────────────────────────────────────────────
    // 搜索
    // ─────────────────────────────────────────────

    public SearchResult search(SearchRequest req, SysUser viewer) {
        if (viewer == null) throw new IllegalStateException("未登录");

        boolean isAdminOrAuditor = "admin".equalsIgnoreCase(viewer.getRole())
                || "auditor".equalsIgnoreCase(viewer.getRole());

        // 1) 决定可见用户集合
        Set<Long> visibleUserIds;
        if (isAdminOrAuditor) {
            // 管理员/审核员全可见，留 null 表示不过滤
            if (req.getUserId() != null) {
                visibleUserIds = Set.of(req.getUserId());
            } else if (req.getDeptId() != null) {
                Set<Long> deptChain = resolveDeptDescendants(req.getDeptId());
                visibleUserIds = userIdsInDepts(deptChain);
            } else {
                visibleUserIds = null;  // 全部
            }
        } else {
            // 普通用户：仅本人
            visibleUserIds = Set.of(viewer.getId());
            // 普通用户不允许越权指定 userId / deptId
            req.setUserId(null);
            req.setDeptId(null);
        }
        if (visibleUserIds != null && visibleUserIds.isEmpty()) {
            return emptyResult(req);
        }

        // 2) 关键词反查命中的 conv_ids
        Set<Long> keywordConvIds = null;
        if (req.getKeyword() != null && !req.getKeyword().isBlank()) {
            keywordConvIds = matchConvIdsByKeyword(req.getKeyword(), visibleUserIds);
            if (keywordConvIds.isEmpty()) return emptyResult(req);
        }

        // 3) 主查 conversation 分页
        Page<QaConversation> page = new Page<>(req.getCurrent(), req.getSize());
        LambdaQueryWrapper<QaConversation> w = new LambdaQueryWrapper<QaConversation>()
                .eq(QaConversation::getDeleted, 0)
                .orderByDesc(QaConversation::getLastActive);
        if (visibleUserIds != null) {
            w.in(QaConversation::getUserId, visibleUserIds);
        }
        if (keywordConvIds != null) {
            w.in(QaConversation::getId, keywordConvIds);
        }
        if (req.getFrom() != null) w.ge(QaConversation::getLastActive, req.getFrom());
        if (req.getTo() != null)   w.le(QaConversation::getLastActive, req.getTo());
        if (Boolean.TRUE.equals(req.getOnlyFlagged())) {
            w.eq(QaConversation::getIsFlagged, 1);
        }
        convMapper.selectPage(page, w);

        // 4) 装配 VO（批量查 user + dept + 命中片段）
        List<QaConversation> records = page.getRecords();
        List<ConvMatchVO> vos = decorate(records, req.getKeyword());

        SearchResult r = new SearchResult();
        r.setTotal(page.getTotal());
        r.setCurrent(page.getCurrent());
        r.setSize(page.getSize());
        r.setRecords(vos);
        return r;
    }

    private Set<Long> matchConvIdsByKeyword(String keyword, Set<Long> visibleUserIds) {
        // LIKE 兼容性好；后续可换 MATCH AGAINST ('xxx' IN NATURAL LANGUAGE MODE)
        LambdaQueryWrapper<QaMessage> w = new LambdaQueryWrapper<QaMessage>()
                .select(QaMessage::getConversationId)
                .like(QaMessage::getContent, keyword)
                .orderByDesc(QaMessage::getId)
                .last("LIMIT " + (MAX_KEYWORD_MATCH_CONV * 5));   // 单 conv 多消息，留余量
        if (visibleUserIds != null && !visibleUserIds.isEmpty()) {
            // 通过 conversation 关联 user_id；用子查询避免大 IN
            List<Long> visConvIds = convMapper.selectList(
                    new LambdaQueryWrapper<QaConversation>()
                            .select(QaConversation::getId)
                            .in(QaConversation::getUserId, visibleUserIds)
                            .eq(QaConversation::getDeleted, 0)
            ).stream().map(QaConversation::getId).toList();
            if (visConvIds.isEmpty()) return Set.of();
            w.in(QaMessage::getConversationId, visConvIds);
        }
        List<QaMessage> hits = messageMapper.selectList(w);
        return hits.stream()
                .map(QaMessage::getConversationId)
                .distinct()
                .limit(MAX_KEYWORD_MATCH_CONV)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<ConvMatchVO> decorate(List<QaConversation> convs, String keyword) {
        if (convs.isEmpty()) return List.of();

        // 批量取 user
        Set<Long> userIds = convs.stream().map(QaConversation::getUserId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysUser> userMap = userIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(userIds).stream()
                    .collect(Collectors.toMap(SysUser::getId, u -> u));

        // 批量取 dept
        Set<Long> deptIds = userMap.values().stream()
                .map(SysUser::getDepartmentId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysDepartment> deptMap = deptIds.isEmpty() ? Map.of()
                : deptMapper.selectBatchIds(deptIds).stream()
                    .collect(Collectors.toMap(SysDepartment::getId, d -> d));

        // 关键词命中片段（仅在有 keyword 时取）
        Map<Long, List<MessageSnippetVO>> snippetMap = (keyword != null && !keyword.isBlank())
                ? loadKeywordSnippets(convs, keyword)
                : Map.of();

        // 标记人 user
        Set<Long> flaggerIds = convs.stream()
                .map(QaConversation::getFlaggedBy).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysUser> flaggerMap = flaggerIds.isEmpty() ? Map.of()
                : userMapper.selectBatchIds(flaggerIds).stream()
                    .collect(Collectors.toMap(SysUser::getId, u -> u));

        List<ConvMatchVO> out = new ArrayList<>(convs.size());
        for (QaConversation c : convs) {
            ConvMatchVO vo = new ConvMatchVO();
            vo.setId(c.getId());
            vo.setTitle(c.getTitle());
            vo.setUserId(c.getUserId());
            vo.setMessageCount(c.getMessageCount());
            vo.setLastActive(c.getLastActive());
            vo.setIsFlagged(c.getIsFlagged());
            vo.setFlagNote(c.getFlagNote());
            vo.setFlaggedAt(c.getFlaggedAt());

            SysUser u = userMap.get(c.getUserId());
            if (u != null) {
                vo.setUsername(u.getUsername());
                vo.setNickname(u.getNickname());
                vo.setDepartmentId(u.getDepartmentId());
                SysDepartment d = u.getDepartmentId() == null ? null : deptMap.get(u.getDepartmentId());
                if (d != null) vo.setDepartmentName(d.getName());
            }
            if (c.getFlaggedBy() != null) {
                SysUser f = flaggerMap.get(c.getFlaggedBy());
                if (f != null) vo.setFlaggedByName(f.getNickname() == null ? f.getUsername() : f.getNickname());
            }
            vo.setMatchedSnippets(snippetMap.getOrDefault(c.getId(), List.of()));
            out.add(vo);
        }
        return out;
    }

    private Map<Long, List<MessageSnippetVO>> loadKeywordSnippets(List<QaConversation> convs, String keyword) {
        Set<Long> ids = convs.stream().map(QaConversation::getId).collect(Collectors.toSet());
        // 每个 conv 最多 3 条命中
        List<QaMessage> hits = messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .in(QaMessage::getConversationId, ids)
                .like(QaMessage::getContent, keyword)
                .orderByAsc(QaMessage::getCreateTime)
                .last("LIMIT " + (ids.size() * MAX_SNIPPETS_PER_CONV)));
        Map<Long, List<MessageSnippetVO>> map = new HashMap<>();
        for (QaMessage m : hits) {
            List<MessageSnippetVO> bucket = map.computeIfAbsent(m.getConversationId(), k -> new ArrayList<>());
            if (bucket.size() >= MAX_SNIPPETS_PER_CONV) continue;
            MessageSnippetVO sv = new MessageSnippetVO();
            sv.setMessageId(m.getId());
            sv.setRole(m.getRole());
            sv.setCreateTime(m.getCreateTime());
            sv.setSnippet(extractSnippet(m.getContent(), keyword));
            bucket.add(sv);
        }
        return map;
    }

    private String extractSnippet(String content, String keyword) {
        if (content == null) return "";
        int idx = content.toLowerCase(Locale.ROOT).indexOf(keyword.toLowerCase(Locale.ROOT));
        if (idx < 0) return content.length() > 80 ? content.substring(0, 80) + "…" : content;
        int s = Math.max(0, idx - SNIPPET_CONTEXT);
        int e = Math.min(content.length(), idx + keyword.length() + SNIPPET_CONTEXT);
        String prefix = s > 0 ? "…" : "";
        String suffix = e < content.length() ? "…" : "";
        return prefix + content.substring(s, e) + suffix;
    }

    // ─────────────────────────────────────────────
    // 详情：查任意 conversation 的完整消息
    // ─────────────────────────────────────────────

    public ConvDetailVO detail(Long convId, SysUser viewer) {
        QaConversation conv = convMapper.selectById(convId);
        if (conv == null || Integer.valueOf(1).equals(conv.getDeleted())) {
            throw new IllegalArgumentException("会话不存在");
        }
        ensureCanView(conv, viewer);

        List<QaMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, convId)
                .orderByAsc(QaMessage::getCreateTime));

        // 装一下 user/dept 头信息（前端展示用）
        ConvDetailVO out = new ConvDetailVO();
        out.setConversation(conv);
        out.setMessages(messages);
        SysUser u = userMapper.selectById(conv.getUserId());
        if (u != null) {
            out.setUsername(u.getUsername());
            out.setNickname(u.getNickname());
            if (u.getDepartmentId() != null) {
                SysDepartment d = deptMapper.selectById(u.getDepartmentId());
                if (d != null) out.setDepartmentName(d.getName());
            }
        }
        return out;
    }

    // ─────────────────────────────────────────────
    // 标记 / 取消标记敏感
    // ─────────────────────────────────────────────

    @Transactional
    public void flag(Long convId, String note, SysUser operator) {
        ensureFlagger(operator);
        QaConversation conv = convMapper.selectById(convId);
        if (conv == null) throw new IllegalArgumentException("会话不存在");
        conv.setIsFlagged(1);
        conv.setFlagNote(note == null ? "" : note.trim());
        conv.setFlaggedBy(operator.getId());
        conv.setFlaggedAt(LocalDateTime.now());
        convMapper.updateById(conv);
        log.info("[ConvFlag] 标记敏感 · conv={} by={} note={}", convId, operator.getId(), note);
    }

    @Transactional
    public void unflag(Long convId, SysUser operator) {
        ensureFlagger(operator);
        QaConversation conv = convMapper.selectById(convId);
        if (conv == null) throw new IllegalArgumentException("会话不存在");
        conv.setIsFlagged(0);
        conv.setFlagNote(null);
        conv.setFlaggedBy(null);
        conv.setFlaggedAt(null);
        convMapper.updateById(conv);
        log.info("[ConvFlag] 取消敏感 · conv={} by={}", convId, operator.getId());
    }

    // ─────────────────────────────────────────────
    // ACL 工具
    // ─────────────────────────────────────────────

    private void ensureCanView(QaConversation conv, SysUser viewer) {
        if (viewer == null) throw new IllegalStateException("未登录");
        if (Objects.equals(viewer.getId(), conv.getUserId())) return;
        String role = viewer.getRole();
        if ("admin".equalsIgnoreCase(role) || "auditor".equalsIgnoreCase(role)) return;
        throw new IllegalStateException("无权查看他人的对话");
    }

    private void ensureFlagger(SysUser viewer) {
        if (viewer == null) throw new IllegalStateException("未登录");
        String role = viewer.getRole();
        if (!"admin".equalsIgnoreCase(role) && !"auditor".equalsIgnoreCase(role)) {
            throw new IllegalStateException("仅管理员/审核员可标记敏感对话");
        }
    }

    private Set<Long> resolveDeptDescendants(Long rootDeptId) {
        if (rootDeptId == null) return Set.of();
        Set<Long> all = new LinkedHashSet<>();
        all.add(rootDeptId);
        Deque<Long> frontier = new ArrayDeque<>();
        frontier.add(rootDeptId);
        int safety = 256;
        while (!frontier.isEmpty() && safety-- > 0) {
            Long cur = frontier.poll();
            List<SysDepartment> children = deptMapper.selectList(
                    new LambdaQueryWrapper<SysDepartment>()
                            .eq(SysDepartment::getParentId, cur)
            );
            for (SysDepartment c : children) {
                if (all.add(c.getId())) frontier.add(c.getId());
            }
        }
        return all;
    }

    private Set<Long> userIdsInDepts(Set<Long> deptIds) {
        if (deptIds.isEmpty()) return Set.of();
        return userMapper.selectList(
                new LambdaQueryWrapper<SysUser>()
                        .select(SysUser::getId)
                        .in(SysUser::getDepartmentId, deptIds)
        ).stream().map(SysUser::getId).collect(Collectors.toSet());
    }

    private SearchResult emptyResult(SearchRequest req) {
        SearchResult r = new SearchResult();
        r.setTotal(0L);
        r.setCurrent(req.getCurrent());
        r.setSize(req.getSize());
        r.setRecords(List.of());
        return r;
    }

    // ─────────────────────────────────────────────
    // DTO
    // ─────────────────────────────────────────────

    @Data
    public static class SearchRequest {
        private String keyword;
        private Long userId;
        private Long deptId;
        private LocalDateTime from;
        private LocalDateTime to;
        private Boolean onlyFlagged;
        private long current = 1L;
        private long size = 20L;
    }

    @Data
    public static class SearchResult {
        private long total;
        private long current;
        private long size;
        private List<ConvMatchVO> records;
    }

    @Data
    public static class ConvMatchVO {
        private Long id;
        private String title;
        private Long userId;
        private String username;
        private String nickname;
        private Long departmentId;
        private String departmentName;
        private Integer messageCount;
        private LocalDateTime lastActive;
        private Integer isFlagged;
        private String flagNote;
        private String flaggedByName;
        private LocalDateTime flaggedAt;
        private List<MessageSnippetVO> matchedSnippets;
    }

    @Data
    public static class MessageSnippetVO {
        private Long messageId;
        private String role;
        private String snippet;
        private LocalDateTime createTime;
    }

    @Data
    public static class ConvDetailVO {
        private QaConversation conversation;
        private String username;
        private String nickname;
        private String departmentName;
        private List<QaMessage> messages;
    }
}
