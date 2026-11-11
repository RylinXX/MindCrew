package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.CoachAnswer;
import com.simon.MindCrew.entity.CoachQuestion;
import com.simon.MindCrew.entity.CoachSession;
import com.simon.MindCrew.service.CoachService;
import com.simon.MindCrew.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 教练模式 API · 任务 9
 *
 *   POST   /api/v2/coach/session                   · 启动一次练习
 *   POST   /api/v2/coach/session/{id}/next         · 出下一题
 *   POST   /api/v2/coach/question/{id}/submit      · 提交答案 + 评分
 *   POST   /api/v2/coach/session/{id}/end          · 主动结束
 *   GET    /api/v2/coach/session/{id}              · 查 session 详情
 *   GET    /api/v2/coach/session/{id}/questions    · 列 session 所有题目 + 答案
 *   GET    /api/v2/coach/sessions                  · 我的历史 sessions
 *   GET    /api/v2/coach/my-stats                  · 我的个人统计
 *   GET    /api/v2/coach/team-stats                · 主管/管理员 · 团队统计
 */
@RestController
@RequestMapping("/api/v2/coach")
@RequiredArgsConstructor
public class CoachController {

    private final CoachService coachService;
    private final UserService userService;

    @PostMapping("/session")
    public Result<CoachSession> startSession(@RequestBody StartReq req) {
        Long uid = userService.getCurrentUserId();
        return Result.success(coachService.startSession(uid, req.getKbIds(), req.getDifficulty(), req.getQuestionTotal()));
    }

    @PostMapping("/session/{id}/next")
    public Result<CoachQuestion> nextQuestion(@PathVariable Long id) {
        Long uid = userService.getCurrentUserId();
        CoachQuestion q = coachService.nextQuestion(id, uid);
        // 出题接口不返回 expected_answer，防止前端泄漏
        q.setExpectedAnswer(null);
        q.setExplanation(null);
        return Result.success(q);
    }

    @PostMapping("/question/{id}/submit")
    public Result<CoachAnswer> submit(@PathVariable Long id, @RequestBody SubmitReq req) {
        Long uid = userService.getCurrentUserId();
        return Result.success(coachService.submitAnswer(id, uid, req.getAnswer()));
    }

    @PostMapping("/session/{id}/end")
    public Result<CoachSession> end(@PathVariable Long id) {
        Long uid = userService.getCurrentUserId();
        return Result.success(coachService.endSession(id, uid));
    }

    @GetMapping("/session/{id}")
    public Result<CoachSession> get(@PathVariable Long id) {
        Long uid = userService.getCurrentUserId();
        return Result.success(coachService.getSession(id, uid));
    }

    /** 详情：返回题目列表 + 对应答案 map（key=questionId） */
    @GetMapping("/session/{id}/questions")
    public Result<Map<String, Object>> sessionDetail(@PathVariable Long id) {
        Long uid = userService.getCurrentUserId();
        coachService.getSession(id, uid);
        List<CoachQuestion> qs = coachService.sessionQuestions(id, uid);
        Map<Long, CoachAnswer> ans = coachService.sessionAnswers(id);

        // 已答的题暴露 expected_answer + explanation，方便复盘；未答的隐藏
        for (CoachQuestion q : qs) {
            if (!ans.containsKey(q.getId())) {
                q.setExpectedAnswer(null);
                q.setExplanation(null);
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("questions", qs);
        body.put("answers", ans);
        return Result.success(body);
    }

    @GetMapping("/sessions")
    public Result<IPage<CoachSession>> mySessions(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "10") int size) {
        Long uid = userService.getCurrentUserId();
        return Result.success(coachService.userSessions(uid, current, size));
    }

    @GetMapping("/my-stats")
    public Result<Map<String, Object>> myStats() {
        Long uid = userService.getCurrentUserId();
        return Result.success(coachService.userStats(uid));
    }

    @GetMapping("/team-stats")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<List<Map<String, Object>>> teamStats(
            @RequestParam(defaultValue = "30") int recentDays) {
        return Result.success(coachService.teamStats(recentDays));
    }

    // ───────── DTO ─────────
    @Data
    public static class StartReq {
        private List<Long> kbIds;
        private String difficulty;       // easy / medium / hard
        private Integer questionTotal;   // 默认 10
    }

    @Data
    public static class SubmitReq {
        private String answer;
    }
}
