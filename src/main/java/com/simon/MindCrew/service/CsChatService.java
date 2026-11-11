package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.simon.MindCrew.agent.MindCrewAgent;
import com.simon.MindCrew.common.exception.BusinessException;
import com.simon.MindCrew.common.result.ResultCode;
import com.simon.MindCrew.controller.CollectingEmitter;
import com.simon.MindCrew.entity.CsConfig;
import com.simon.MindCrew.entity.CsSession;
import com.simon.MindCrew.entity.SysUser;
import com.simon.MindCrew.mapper.SysUserMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 客服中心 · AI 回答
 *
 * 复用 {@link MindCrewAgent} + {@link CollectingEmitter}：
 *   - 异步线程池（daemon，4 线程）执行，POST 消息立即返回 pending=true，前端轮询
 *   - 用专用机器人账号 cs_robot（role=admin）调 Agent，解决 KB ACL 交集问题
 *   - 首次回答后把 qa_conversation.id 回写 cs_session.conversation_id，实现多轮上下文
 *   - 异常必须落 ai 兜底消息并把会话转人工
 */
@Slf4j
@Service
public class CsChatService {

    /** AI 异常兜底话术（契约 3.1） */
    public static final String FALLBACK_ANSWER = "抱歉，我暂时无法回答，已通知人工客服";

    private static final String ROBOT_USERNAME = "cs_robot";
    private static final String ROBOT_NICKNAME = "智能客服";
    private static final int AI_POOL_SIZE = 4;

    private final MindCrewAgent agent;
    private final CsSessionService sessionService;
    private final CsConfigService configService;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final ExecutorService aiExecutor;

    /** 机器人账号 id（懒加载 + 单例缓存） */
    private volatile Long robotUserId;

    public CsChatService(MindCrewAgent agent,
                         CsSessionService sessionService,
                         CsConfigService configService,
                         SysUserMapper sysUserMapper,
                         PasswordEncoder passwordEncoder) {
        this.agent = agent;
        this.sessionService = sessionService;
        this.configService = configService;
        this.sysUserMapper = sysUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.aiExecutor = Executors.newFixedThreadPool(AI_POOL_SIZE, r -> {
            Thread t = new Thread(r, "cs-ai-reply");
            t.setDaemon(true);
            return t;
        });
    }

    // ─────────────────────────────────────────────
    // 访客发消息
    // ─────────────────────────────────────────────

    /**
     * 公开页发消息：落 user 消息 → 转人工判定 / 异步 AI。
     * 返回 {messageId, pending, status}；pending=true 时前端轮询 /messages。
     */
    public Map<String, Object> sendMessage(String sessionNo, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "消息内容不能为空");
        }
        CsSession session = sessionService.requireBySessionNo(sessionNo);
        if (CsSessionService.STATUS_CLOSED.equals(session.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "会话已关闭");
        }

        String question = content.trim();
        Long messageId = sessionService.appendMessage(session.getId(), "user", question, null, null).getId();

        CsConfig cfg = configService.getEntity();
        boolean aiEnabled = cfg.getAiEnabled() == null || cfg.getAiEnabled() == 1;
        boolean handoff = configService.hitHandoffKeyword(question, cfg);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messageId", messageId);

        // 已转人工：消息落库后等人工回复，不再触发 AI
        if (CsSessionService.STATUS_HUMAN.equals(session.getStatus())) {
            body.put("pending", false);
            body.put("status", CsSessionService.STATUS_HUMAN);
            return body;
        }

        if (!aiEnabled || handoff) {
            sessionService.handoff(session.getId());
            body.put("pending", false);
            body.put("status", CsSessionService.STATUS_HUMAN);
            return body;
        }

        body.put("pending", true);
        body.put("status", session.getStatus());
        submitAnswer(session.getId(), question);
        return body;
    }

    /** 异步提交 AI 回答任务 */
    private void submitAnswer(Long sessionId, String question) {
        aiExecutor.submit(() -> {
            try {
                answerForSession(sessionId, question);
            } catch (Throwable t) {
                // answerForSession 内部已兜底；这里只兜极端情况，避免线程池任务静默失败
                log.error("[CS] 异步 AI 任务异常 sessionId={}", sessionId, t);
            }
        });
    }

    // ─────────────────────────────────────────────
    // AI 回答（同步执行体，异步/钉钉共用）
    // ─────────────────────────────────────────────

    /**
     * 为某个会话生成 AI 回答并落库（同步）。
     * 成功写 role=ai 消息 + sources；失败写兜底消息并转人工。
     */
    public String answerForSession(Long sessionId, String question) {
        CsSession session = sessionService.requireById(sessionId);
        CsConfig cfg = configService.getEntity();
        List<Long> kbIds = resolveKbIds(session, cfg);

        CollectingEmitter emitter = new CollectingEmitter();
        Long conversationId = null;
        try {
            conversationId = agent.execute(String.valueOf(robotUserId()), session.getConversationId(),
                    question, kbIds, List.of(), emitter);
            if (conversationId != null) {
                sessionService.updateConversationId(sessionId, conversationId);
            }
            String answer = emitter.getAnswer();
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("AI 返回空答案");
            }
            sessionService.appendMessage(sessionId, "ai", answer,
                    JSON.toJSONString(emitter.getSources()), null);
            return answer;
        } catch (Throwable t) {
            log.error("[CS] AI 回答失败 sessionId={} question={}", sessionId, question, t);
            if (conversationId != null) {
                try {
                    sessionService.updateConversationId(sessionId, conversationId);
                } catch (Exception ignored) {
                    // 回写失败不影响兜底
                }
            }
            try {
                sessionService.appendMessage(sessionId, "ai", FALLBACK_ANSWER, null, null);
                sessionService.markHuman(sessionId);
            } catch (Exception e) {
                log.error("[CS] AI 兜底消息写入失败 sessionId={}", sessionId, e);
            }
            return FALLBACK_ANSWER;
        }
    }

    /** 会话自身范围优先（钉钉渠道可独立配置），否则回落到 cs_config.kb_ids */
    private List<Long> resolveKbIds(CsSession session, CsConfig cfg) {
        if (session.getKbIds() != null && !session.getKbIds().isBlank()) {
            try {
                List<Long> fromSession = JSON.parseArray(session.getKbIds(), Long.class);
                if (fromSession != null) return fromSession;
            } catch (Exception e) {
                log.warn("[CS] cs_session.kb_ids 解析失败 sessionId={} raw={}", session.getId(), session.getKbIds());
            }
        }
        return configService.kbIds(cfg);
    }

    /** 一次性回答（不建会话、不落库）· 管理端钉钉「测试」用 */
    public Map<String, Object> answerOnly(String question, List<Long> kbIds) {
        if (question == null || question.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "消息内容不能为空");
        }
        CollectingEmitter emitter = new CollectingEmitter();
        try {
            Long conversationId = agent.execute(String.valueOf(robotUserId()), null, question.trim(),
                    kbIds == null ? List.of() : kbIds, List.of(), emitter);
            String answer = emitter.getAnswer();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("answer", answer);
            body.put("sources", emitter.getSources());
            body.put("conversationId", conversationId);
            return body;
        } catch (Exception e) {
            log.error("[CS] 测试回答失败 question={}", question, e);
            throw new BusinessException(ResultCode.ERROR.getCode(), "AI 生成失败：" + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 机器人账号
    // ─────────────────────────────────────────────

    /**
     * 客服专用机器人账号（username=cs_robot）：
     * 不存在则插入 sys_user（role=admin、status=1、随机 BCrypt 密码）。
     * admin 身份让 Agent 的 KB ACL 交集 == cs_config.kb_ids。
     */
    public Long robotUserId() {
        Long cached = robotUserId;
        if (cached != null) return cached;
        synchronized (this) {
            if (robotUserId != null) return robotUserId;
            SysUser robot = null;
            try {
                robot = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                        .eq(SysUser::getUsername, ROBOT_USERNAME)
                        .last("LIMIT 1"));
            } catch (Exception e) {
                log.warn("[CS] 查询机器人账号失败: {}", e.getMessage());
            }
            if (robot == null) {
                robot = new SysUser();
                robot.setUsername(ROBOT_USERNAME);
                robot.setNickname(ROBOT_NICKNAME);
                robot.setRole("admin");
                robot.setStatus(1);
                robot.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
                try {
                    sysUserMapper.insert(robot);
                    log.info("[CS] 已创建客服机器人账号 id={}", robot.getId());
                } catch (Exception e) {
                    // 并发下唯一键冲突：回查一次
                    log.warn("[CS] 创建机器人账号冲突，回查: {}", e.getMessage());
                    robot = sysUserMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                            .eq(SysUser::getUsername, ROBOT_USERNAME)
                            .last("LIMIT 1"));
                    if (robot == null) {
                        throw new BusinessException(ResultCode.ERROR.getCode(), "客服机器人账号初始化失败");
                    }
                }
            }
            robotUserId = robot.getId();
            return robotUserId;
        }
    }

    @PreDestroy
    public void shutdown() {
        aiExecutor.shutdown();
        try {
            if (!aiExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                aiExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            aiExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("[CS] AI 线程池已关闭");
    }
}
