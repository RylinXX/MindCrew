package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.entity.CsConfig;
import com.simon.MindCrew.entity.CsMessage;
import com.simon.MindCrew.entity.CsSession;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.mapper.CsMessageMapper;
import com.simon.MindCrew.mapper.CsSessionMapper;
import com.simon.MindCrew.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客服中心 · 会话生命周期
 *
 * 覆盖：建会话 / 轮询消息 / 发消息落库 / 转人工 / 认领 / 回复 / 转接 / 关闭 / 满意度 / 统计。
 * AI 回答由 {@link CsChatService} 异步产出，本类只负责消息与状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsSessionService {

    public static final String STATUS_BOT = "bot";
    public static final String STATUS_HUMAN = "human";
    public static final String STATUS_CLOSED = "closed";

    private static final String SYSTEM_HANDOFF_MESSAGE = "已为您转接人工客服";
    private static final DateTimeFormatter SESSION_NO_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter TREND_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final CsSessionMapper sessionMapper;
    private final CsMessageMapper messageMapper;
    private final SysUserMapper userMapper;
    private final CsConfigService configService;
    private final CsTokenService tokenService;

    // ─────────────────────────────────────────────
    // 建会话
    // ─────────────────────────────────────────────

    /** 公开页建会话（检索范围取 cs_config.kb_ids；sourceAppId/ssoUserId 由调用方可选传入） */
    public Map<String, Object> createVisitorSession(String visitorKey, String visitorName, String channel,
                                                    String sourceAppId, Long ssoUserId,
                                                    String ip, String userAgent) {
        CsConfig cfg = configService.getEntity();
        CsSession session = createSession(visitorKey, visitorName, channel, sourceAppId,
                cfg.getKbIds(), ssoUserId, ip, userAgent);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionId", session.getId());
        body.put("sessionNo", session.getSessionNo());
        body.put("visitorToken", tokenService.issueVisitorToken(session.getSessionNo()));
        body.put("status", session.getStatus());
        body.put("welcome", configService.welcomeText(cfg));
        body.put("quickQuestions", configService.quickQuestions(cfg));
        return body;
    }

    /** 建会话（kbIdsJson 由调用方指定；钉钉渠道用渠道自己的范围）· 字段按列长截断 */
    public CsSession createSession(String visitorKey, String visitorName, String channel, String sourceAppId,
                                   String kbIdsJson, Long ssoUserId, String ip, String userAgent) {
        CsSession s = new CsSession();
        s.setSessionNo(generateSessionNo());
        String finalVisitorKey = StringUtils.hasText(visitorKey)
                ? visitorKey.trim() : ("guest_" + randomHex(12));
        s.setVisitorKey(truncate(finalVisitorKey, 128));
        s.setVisitorName(truncate(visitorName, 100));
        s.setSourceAppId(truncate(sourceAppId, 64));
        String finalChannel = StringUtils.hasText(channel) ? channel.trim() : "web";
        s.setChannel(truncate(finalChannel, 20));
        s.setKbIds(kbIdsJson);
        s.setStatus(STATUS_BOT);
        s.setMessageCount(0);
        s.setLastActive(LocalDateTime.now());
        s.setSsoUserId(ssoUserId);
        s.setIp(ip);
        s.setUserAgent(truncate(userAgent, 500));
        sessionMapper.insert(s);
        return s;
    }

    /** 钉钉按 sender 复用未关闭会话，无则新建（visitorKey 先截断，保证查询/写入一致） */
    public CsSession findOrCreateForChannel(String visitorKey, String visitorName, String channel,
                                            String sourceAppId, String kbIdsJson) {
        String key = truncate(visitorKey == null ? "" : visitorKey.trim(), 128);
        CsSession existing = sessionMapper.selectOne(new LambdaQueryWrapper<CsSession>()
                .eq(CsSession::getVisitorKey, key)
                .eq(CsSession::getChannel, channel)
                .ne(CsSession::getStatus, STATUS_CLOSED)
                .orderByDesc(CsSession::getLastActive)
                .last("LIMIT 1"));
        if (existing != null) return existing;
        return createSession(key, truncate(visitorName, 100), channel, sourceAppId, kbIdsJson, null, null, null);
    }

    // ─────────────────────────────────────────────
    // 查询 / 校验
    // ─────────────────────────────────────────────

    public CsSession getById(Long id) {
        return id == null ? null : sessionMapper.selectById(id);
    }

    public CsSession requireById(Long id) {
        CsSession s = getById(id);
        if (s == null) throw new BusinessException(ResultCode.CONVERSATION_NOT_FOUND.getCode(), "会话不存在");
        return s;
    }

    public CsSession findBySessionNo(String sessionNo) {
        if (!StringUtils.hasText(sessionNo)) return null;
        return sessionMapper.selectOne(new LambdaQueryWrapper<CsSession>()
                .eq(CsSession::getSessionNo, sessionNo.trim())
                .last("LIMIT 1"));
    }

    public CsSession requireBySessionNo(String sessionNo) {
        CsSession s = findBySessionNo(sessionNo);
        if (s == null) throw new BusinessException(ResultCode.CONVERSATION_NOT_FOUND.getCode(), "会话不存在");
        return s;
    }

    /** 公开接口鉴权：token 必须有效且与 sessionNo 匹配 */
    public CsSession verifyVisitor(String token, String sessionNo) {
        String tokenSessionNo = tokenService.verifyVisitorToken(token);
        if (!StringUtils.hasText(sessionNo)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "sessionNo 不能为空");
        }
        if (!tokenSessionNo.equals(sessionNo.trim())) {
            throw new BusinessException(401, "访客凭证无效");
        }
        CsSession s = findBySessionNo(sessionNo);
        if (s == null) throw new BusinessException(ResultCode.CONVERSATION_NOT_FOUND.getCode(), "会话不存在");
        return s;
    }

    // ─────────────────────────────────────────────
    // 消息
    // ─────────────────────────────────────────────

    public CsMessage appendMessage(Long sessionId, String role, String content, String sourcesJson, Long senderId) {
        CsSession s = requireById(sessionId);
        CsMessage m = new CsMessage();
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        m.setSources(sourcesJson);
        m.setSenderId(senderId);
        messageMapper.insert(m);

        s.setMessageCount((s.getMessageCount() == null ? 0 : s.getMessageCount()) + 1);
        s.setLastMessage(truncate(content, 500));
        s.setLastActive(LocalDateTime.now());
        sessionMapper.updateById(s);
        return m;
    }

    public void updateConversationId(Long sessionId, Long conversationId) {
        if (sessionId == null || conversationId == null) return;
        CsSession s = new CsSession();
        s.setId(sessionId);
        s.setConversationId(conversationId);
        sessionMapper.updateById(s);
    }

    /** 公开页轮询：返回状态 + 坐席名 + sinceId 之后的新消息 */
    public Map<String, Object> pollMessages(String sessionNo, Long sinceId) {
        CsSession s = requireBySessionNo(sessionNo);
        long after = sinceId == null ? 0L : sinceId;
        List<CsMessage> list = messageMapper.selectList(new LambdaQueryWrapper<CsMessage>()
                .eq(CsMessage::getSessionId, s.getId())
                .gt(CsMessage::getId, after)
                .orderByAsc(CsMessage::getId));

        List<Map<String, Object>> messages = new ArrayList<>();
        for (CsMessage m : list) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", m.getId());
            item.put("role", m.getRole());
            item.put("content", m.getContent());
            item.put("sources", m.getSources());
            item.put("createTime", m.getCreateTime());
            messages.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", s.getStatus());
        body.put("assigneeName", resolveAssigneeName(s.getAssigneeId()));
        body.put("messages", messages);
        return body;
    }

    // ─────────────────────────────────────────────
    // 状态流转
    // ─────────────────────────────────────────────

    @Transactional
    public CsSession handoff(Long sessionId) {
        CsSession s = requireById(sessionId);
        // 幂等：已在人工状态，不重复追加转接系统消息
        if (STATUS_HUMAN.equals(s.getStatus())) {
            return s;
        }
        if (!STATUS_CLOSED.equals(s.getStatus())) {
            s.setStatus(STATUS_HUMAN);
            sessionMapper.updateById(s);
        }
        appendMessage(sessionId, "system", SYSTEM_HANDOFF_MESSAGE, null, null);
        return s;
    }

    public void markHuman(Long sessionId) {
        CsSession s = requireById(sessionId);
        if (!STATUS_CLOSED.equals(s.getStatus())) {
            s.setStatus(STATUS_HUMAN);
            sessionMapper.updateById(s);
        }
    }

    public Integer rate(Long sessionId, Integer rating, String note) {
        if (rating == null || rating < 1 || rating > 5) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "评分必须在 1-5 之间");
        }
        CsSession s = requireById(sessionId);
        s.setRating(rating);
        s.setRatingNote(truncate(note, 500));
        sessionMapper.updateById(s);
        return rating;
    }

    public CsSession close(Long sessionId) {
        CsSession s = requireById(sessionId);
        s.setStatus(STATUS_CLOSED);
        s.setLastActive(LocalDateTime.now());
        sessionMapper.updateById(s);
        return s;
    }

    // ─────────────────────────────────────────────
    // 工作台
    // ─────────────────────────────────────────────

    public Page<CsSession> pageSessions(Integer current, Integer size, String status, String channel, String keyword) {
        int pageNo = current == null || current < 1 ? 1 : current;
        int pageSize = size == null || size < 1 ? 10 : Math.min(size, 200);
        Page<CsSession> page = new Page<>(pageNo, pageSize);

        LambdaQueryWrapper<CsSession> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(status)) wrapper.eq(CsSession::getStatus, status.trim());
        if (StringUtils.hasText(channel)) wrapper.eq(CsSession::getChannel, channel.trim());
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            wrapper.and(w -> w.like(CsSession::getSessionNo, kw)
                    .or().like(CsSession::getVisitorName, kw)
                    .or().like(CsSession::getLastMessage, kw));
        }
        wrapper.orderByDesc(CsSession::getLastActive);

        Page<CsSession> result = sessionMapper.selectPage(page, wrapper);
        fillAssigneeNames(result.getRecords());
        return result;
    }

    public Map<String, Object> detail(Long id) {
        CsSession s = requireById(id);
        List<CsMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<CsMessage>()
                .eq(CsMessage::getSessionId, id)
                .orderByAsc(CsMessage::getId));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session", s);
        body.put("assigneeName", resolveAssigneeName(s.getAssigneeId()));
        body.put("messages", messages);
        return body;
    }

    public CsSession claim(Long sessionId, Long userId) {
        CsSession s = requireById(sessionId);
        s.setStatus(STATUS_HUMAN);
        s.setAssigneeId(userId);
        s.setLastActive(LocalDateTime.now());
        sessionMapper.updateById(s);
        return s;
    }

    public CsMessage reply(Long sessionId, Long userId, String content) {
        if (!StringUtils.hasText(content)) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "回复内容不能为空");
        }
        CsSession s = requireById(sessionId);
        if (STATUS_CLOSED.equals(s.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "会话已关闭，无法回复");
        }
        CsMessage m = appendMessage(sessionId, "agent", content.trim(), null, userId);
        // appendMessage 已更新消息计数/最后消息，重新取最新会话再改状态，避免旧值覆盖
        CsSession latest = requireById(sessionId);
        latest.setStatus(STATUS_HUMAN);
        if (latest.getAssigneeId() == null) latest.setAssigneeId(userId);
        sessionMapper.updateById(latest);
        return m;
    }

    public CsSession transfer(Long sessionId, Long assigneeId) {
        if (assigneeId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "转接坐席不能为空");
        }
        SysUser user = userMapper.selectById(assigneeId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND.getCode(), "转接坐席不存在");
        }
        CsSession s = requireById(sessionId);
        s.setAssigneeId(assigneeId);
        s.setStatus(STATUS_HUMAN);
        s.setLastActive(LocalDateTime.now());
        sessionMapper.updateById(s);
        return s;
    }

    // ─────────────────────────────────────────────
    // 统计
    // ─────────────────────────────────────────────

    public Map<String, Object> stats(Integer days) {
        int d = days == null || days < 1 ? 7 : Math.min(days, 90);
        LocalDate today = LocalDate.now();
        LocalDateTime from = today.minusDays(d - 1L).atStartOfDay();

        List<CsSession> list = sessionMapper.selectList(new LambdaQueryWrapper<CsSession>()
                .ge(CsSession::getCreateTime, from));

        long sessions = list.size();
        long aiSessions = 0;
        long handoffCount = 0;
        long rated = 0;
        long satisfied = 0;
        long ratingSum = 0;
        for (CsSession s : list) {
            if (s.getConversationId() != null) aiSessions++;
            if (s.getAssigneeId() != null || STATUS_HUMAN.equals(s.getStatus())) handoffCount++;
            if (s.getRating() != null) {
                rated++;
                ratingSum += s.getRating();
                if (s.getRating() >= 4) satisfied++;
            }
        }

        Map<String, Long> statusCount = new LinkedHashMap<>();
        statusCount.put(STATUS_BOT, 0L);
        statusCount.put(STATUS_HUMAN, 0L);
        statusCount.put(STATUS_CLOSED, 0L);
        Map<LocalDate, Long> trendCount = new HashMap<>();
        for (CsSession s : list) {
            String st = s.getStatus() == null ? STATUS_BOT : s.getStatus();
            statusCount.merge(st, 1L, Long::sum);
            if (s.getCreateTime() != null) {
                trendCount.merge(s.getCreateTime().toLocalDate(), 1L, Long::sum);
            }
        }

        List<Map<String, Object>> statusDist = new ArrayList<>();
        for (Map.Entry<String, Long> e : statusCount.entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", e.getKey());
            item.put("count", e.getValue());
            statusDist.add(item);
        }

        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = d - 1; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", date.format(TREND_DATE_FORMATTER));
            item.put("count", trendCount.getOrDefault(date, 0L));
            trend.add(item);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", sessions);
        body.put("aiSessions", aiSessions);
        body.put("handoffCount", handoffCount);
        body.put("handoffRate", sessions == 0 ? 0.0 : round2(handoffCount * 100.0 / sessions));
        body.put("avgRating", rated == 0 ? 0.0 : round2(ratingSum * 1.0 / rated));
        body.put("satisfactionRate", rated == 0 ? 0.0 : round2(satisfied * 100.0 / rated));
        body.put("statusDist", statusDist);
        body.put("trend", trend);
        return body;
    }

    public long countTotal() {
        return sessionMapper.selectCount(new LambdaQueryWrapper<CsSession>());
    }

    public long countToday() {
        return sessionMapper.selectCount(new LambdaQueryWrapper<CsSession>()
                .ge(CsSession::getCreateTime, LocalDate.now().atStartOfDay()));
    }

    // ─────────────────────────────────────────────
    // 内部工具
    // ─────────────────────────────────────────────

    private void fillAssigneeNames(List<CsSession> records) {
        if (records == null || records.isEmpty()) return;
        List<Long> ids = records.stream()
                .map(CsSession::getAssigneeId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (ids.isEmpty()) return;
        Map<Long, String> nameMap = new HashMap<>();
        for (SysUser u : userMapper.selectBatchIds(ids)) {
            nameMap.put(u.getId(), StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername());
        }
        for (CsSession s : records) {
            s.setAssigneeName(nameMap.get(s.getAssigneeId()));
        }
    }

    private String resolveAssigneeName(Long assigneeId) {
        if (assigneeId == null) return null;
        SysUser u = userMapper.selectById(assigneeId);
        if (u == null) return null;
        return StringUtils.hasText(u.getNickname()) ? u.getNickname() : u.getUsername();
    }

    private static String generateSessionNo() {
        return "CS" + LocalDateTime.now().format(SESSION_NO_FORMATTER)
                + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
    }

    private static String randomHex(int len) {
        String hex = UUID.randomUUID().toString().replace("-", "");
        return hex.length() > len ? hex.substring(0, len) : hex;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
