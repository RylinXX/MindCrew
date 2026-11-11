package com.simon.MindCrew.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.simon.MindCrew.common.result.Result;
import com.simon.MindCrew.entity.QaFeedback;
import com.simon.MindCrew.service.QaFeedbackService;
import com.simon.MindCrew.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 反馈 API
 *   POST   /api/v2/feedback                · 用户提交（rating + comment + correction_text）
 *   GET    /api/v2/feedback/page           · 审核员列表（带 status / rating 过滤）
 *   POST   /api/v2/feedback/{id}/reject    · 审核员拒绝
 *   GET    /api/v2/feedback/count          · 待审核 / 已收录 计数
 */
@RestController
@RequestMapping("/api/v2/feedback")
@RequiredArgsConstructor
public class QaFeedbackController {

    private final QaFeedbackService feedbackService;
    private final UserService userService;

    @PostMapping
    public Result<Long> submit(@RequestBody SubmitDTO dto) {
        Long userId = userService.getCurrentUserId();
        Long id = feedbackService.submit(
                dto.getMessageId(),
                userId,
                dto.getRating(),
                dto.getComment(),
                dto.getCorrectionText()
        );
        return Result.success(id);
    }

    @GetMapping("/page")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<IPage<QaFeedback>> page(
            @RequestParam(defaultValue = "1") int current,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String rating) {
        return Result.success(feedbackService.page(current, size, status, rating));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<QaFeedback> get(@PathVariable Long id) {
        return Result.success(feedbackService.getById(id));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<Void> reject(@PathVariable Long id, @RequestBody RejectDTO dto) {
        Long reviewerId = userService.getCurrentUserId();
        feedbackService.reject(id, reviewerId, dto.getNote());
        return Result.success();
    }

    @GetMapping("/count")
    @PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")
    public Result<Map<String, Long>> count() {
        return Result.success(Map.of(
                "pending",  feedbackService.countByStatus(QaFeedbackService.STATUS_PENDING),
                "approved", feedbackService.countByStatus(QaFeedbackService.STATUS_APPROVED),
                "rejected", feedbackService.countByStatus(QaFeedbackService.STATUS_REJECTED)
        ));
    }

    @Data
    public static class SubmitDTO {
        private Long messageId;
        private String rating;          // up / down
        private String comment;
        private String correctionText;
    }

    @Data
    public static class RejectDTO {
        private String note;
    }
}
