package com.simon.MindCrew.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.QaFeedback;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.QaFeedbackMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 用户反馈服务。
 *
 * 流程：
 *   1. 用户在 ChatView 点 👍/👎 → submit() 入库（status=pending）
 *   2. 用户可以"我来纠正" → 带 correction_text 提交
 *   3. 审核员去后台 → list(status=pending)
 *   4. 审核员 approve(id, finalAnswer) → 走 QaGoldenPairService.createFromFeedback
 *   5. 审核员 reject(id, note) → 关闭，不入 golden pair
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaFeedbackService {

    private final QaFeedbackMapper feedbackMapper;
    private final QaMessageMapper messageMapper;

    public static final String STATUS_PENDING  = "pending";
    public static final String STATUS_APPROVED = "approved";
    public static final String STATUS_REJECTED = "rejected";

    /**
     * 提交反馈。同一 message + user 允许覆盖（视为修改打分）。
     */
    @Transactional
    public Long submit(Long messageId, Long userId, String rating, String comment, String correctionText) {
        if (messageId == null || userId == null || rating == null) {
            throw new IllegalArgumentException("messageId / userId / rating 必填");
        }
        if (!"up".equals(rating) && !"down".equals(rating)) {
            throw new IllegalArgumentException("rating 必须是 up 或 down");
        }
        QaMessage msg = messageMapper.selectById(messageId);
        if (msg == null) throw new IllegalArgumentException("消息不存在: " + messageId);
        if (!"assistant".equals(msg.getRole())) {
            throw new IllegalArgumentException("只能对 AI 回答消息提交反馈");
        }

        // 已有反馈则更新
        QaFeedback existing = feedbackMapper.selectOne(new LambdaQueryWrapper<QaFeedback>()
                .eq(QaFeedback::getMessageId, messageId)
                .eq(QaFeedback::getUserId, userId)
                .last("LIMIT 1"));
        if (existing != null) {
            existing.setRating(rating);
            existing.setComment(comment);
            if (correctionText != null && !correctionText.isBlank()) {
                existing.setCorrectionText(correctionText);
                if (STATUS_REJECTED.equals(existing.getStatus())) {
                    existing.setStatus(STATUS_PENDING);  // 用户再次纠正 → 重新待审
                }
            }
            feedbackMapper.updateById(existing);
            log.info("[Feedback] 更新 id={} rating={} hasCorrection={}", existing.getId(), rating,
                    correctionText != null && !correctionText.isBlank());
            return existing.getId();
        }

        QaFeedback fb = new QaFeedback();
        fb.setMessageId(messageId);
        fb.setConversationId(msg.getConversationId());
        fb.setUserId(userId);
        fb.setRating(rating);
        fb.setComment(comment);
        fb.setCorrectionText(correctionText);
        fb.setStatus(STATUS_PENDING);
        feedbackMapper.insert(fb);
        log.info("[Feedback] 新建 id={} message={} rating={}", fb.getId(), messageId, rating);
        return fb.getId();
    }

    /** 审核拒绝（不进 golden pair） */
    @Transactional
    public void reject(Long feedbackId, Long reviewerId, String note) {
        QaFeedback fb = feedbackMapper.selectById(feedbackId);
        if (fb == null) throw new IllegalArgumentException("反馈不存在");
        fb.setStatus(STATUS_REJECTED);
        fb.setReviewerId(reviewerId);
        fb.setReviewerNote(note);
        fb.setReviewedAt(LocalDateTime.now());
        feedbackMapper.updateById(fb);
        log.info("[Feedback] reject id={} by reviewer={}", feedbackId, reviewerId);
    }

    /** 标记为已收录（在 GoldenPairService.createFromFeedback 内部调用） */
    @Transactional
    public void markApproved(Long feedbackId, Long reviewerId, Long goldenPairId) {
        QaFeedback fb = feedbackMapper.selectById(feedbackId);
        if (fb == null) return;
        fb.setStatus(STATUS_APPROVED);
        fb.setReviewerId(reviewerId);
        fb.setReviewedAt(LocalDateTime.now());
        fb.setGoldenPairId(goldenPairId);
        feedbackMapper.updateById(fb);
    }

    public QaFeedback getById(Long id) { return feedbackMapper.selectById(id); }

    public IPage<QaFeedback> page(int current, int size, String status, String rating) {
        Page<QaFeedback> page = new Page<>(current, size);
        return feedbackMapper.selectPage(page, new LambdaQueryWrapper<QaFeedback>()
                .eq(status != null && !status.isBlank(), QaFeedback::getStatus, status)
                .eq(rating != null && !rating.isBlank(), QaFeedback::getRating, rating)
                .orderByDesc(QaFeedback::getCreateTime));
    }

    public Long countByStatus(String status) {
        return feedbackMapper.selectCount(new LambdaQueryWrapper<QaFeedback>()
                .eq(QaFeedback::getStatus, status));
    }
}
