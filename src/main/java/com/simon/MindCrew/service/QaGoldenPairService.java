package com.simon.MindCrew.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.simon.MindCrew.entity.QaFeedback;
import com.simon.MindCrew.entity.QaGoldenPair;
import com.simon.MindCrew.entity.QaMessage;
import com.simon.MindCrew.mapper.QaGoldenPairMapper;
import com.simon.MindCrew.mapper.QaMessageMapper;
import com.simon.MindCrew.service.knowledge.GoldenPairMilvusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Golden Pair 服务 · 任务 6 核心
 *
 * 职责：
 *   1. createFromFeedback(feedbackId, finalAnswer, reviewerId)
 *        审核员认可一条反馈 → 写 DB + 写 Milvus
 *   2. searchHit(query)
 *        Agent 在 RAG 主流程最前端调用，命中直接返回 standard_answer
 *   3. 普通 CRUD（管理员可以直接编辑 / 禁用 / 删除）
 *   4. 命中计数：每次命中递增 hit_count、刷新 last_hit_at
 *
 * 不做 mock，不做兜底：
 *   - DB 写入失败抛异常
 *   - Milvus 写入失败回滚 DB
 *   - 删除 DB 时同步删除 Milvus
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QaGoldenPairService {

    private final QaGoldenPairMapper goldenMapper;
    private final QaMessageMapper messageMapper;
    private final QaFeedbackService feedbackService;
    private final GoldenPairMilvusService goldenMilvus;
    private final EmbeddingModel embeddingModel;

    // ─────────────────────────────────────────────
    // 写入
    // ─────────────────────────────────────────────

    /**
     * 审核员认可反馈 → 创建 Golden Pair。
     *
     * 优先级：
     *   1. finalAnswer 参数（审核员手填）
     *   2. feedback.correctionText（用户自己提供的纠正）
     *   3. 抛错（没有合格答案不能入库）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createFromFeedback(Long feedbackId, String finalAnswer, Long reviewerId) {
        QaFeedback fb = feedbackService.getById(feedbackId);
        if (fb == null) throw new IllegalArgumentException("反馈不存在: " + feedbackId);

        // 取问题原文（从对应 user message 拿）
        QaMessage aiMsg = messageMapper.selectById(fb.getMessageId());
        if (aiMsg == null) throw new IllegalArgumentException("反馈关联的 AI 消息已被删除");
        // 找该 conversation 中 aiMsg 之前那条 user message
        QaMessage userMsg = messageMapper.selectOne(new LambdaQueryWrapper<QaMessage>()
                .eq(QaMessage::getConversationId, fb.getConversationId())
                .eq(QaMessage::getRole, "user")
                .lt(QaMessage::getId, aiMsg.getId())
                .orderByDesc(QaMessage::getId)
                .last("LIMIT 1"));
        if (userMsg == null || userMsg.getContent() == null || userMsg.getContent().isBlank()) {
            throw new IllegalArgumentException("找不到对应的用户提问");
        }
        String question = userMsg.getContent().trim();

        // 选最终答案
        String answer = finalAnswer;
        if (answer == null || answer.isBlank()) answer = fb.getCorrectionText();
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("没有合格的标准答案 · finalAnswer 或 correctionText 必填其一");
        }
        answer = answer.trim();

        return create(question, answer, fb.getCorrectionSources(), reviewerId, feedbackId);
    }

    /**
     * 直接新建一条 Golden Pair（管理员手动录入，不来自反馈）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long create(String question, String answer, String sourcesJson, Long createdBy, Long sourceFeedbackId) {
        if (question == null || question.isBlank()) throw new IllegalArgumentException("question 必填");
        if (answer == null || answer.isBlank())     throw new IllegalArgumentException("answer 必填");

        String norm = normalize(question);

        // 重复检测：归一化后的问题已存在 → 更新而不是插入
        QaGoldenPair existing = goldenMapper.selectOne(new LambdaQueryWrapper<QaGoldenPair>()
                .eq(QaGoldenPair::getQuestionNorm, norm)
                .last("LIMIT 1"));

        List<Float> emb = embed(question);

        if (existing != null) {
            existing.setQuestion(question.trim());
            existing.setStandardAnswer(answer);
            existing.setSourcesJson(sourcesJson);
            existing.setEnabled(1);
            goldenMapper.updateById(existing);
            // 重写 Milvus
            goldenMilvus.upsert(existing.getId(), existing.getMilvusId(), emb);
            if (sourceFeedbackId != null) {
                feedbackService.markApproved(sourceFeedbackId, createdBy, existing.getId());
            }
            log.info("[GoldenPair] 已存在 · 更新 id={} norm={}", existing.getId(), norm);
            return existing.getId();
        }

        QaGoldenPair pair = new QaGoldenPair();
        pair.setQuestion(question.trim());
        pair.setQuestionNorm(norm);
        pair.setStandardAnswer(answer);
        pair.setSourcesJson(sourcesJson);
        pair.setMilvusId("gp-" + UUID.randomUUID());
        pair.setSourceFeedbackId(sourceFeedbackId);
        pair.setEnabled(1);
        pair.setHitCount(0);
        pair.setCreatedBy(createdBy);
        goldenMapper.insert(pair);

        try {
            goldenMilvus.upsert(pair.getId(), pair.getMilvusId(), emb);
        } catch (Exception e) {
            // Milvus 写失败 → 抛异常让事务回滚
            throw new RuntimeException("写入 Milvus 失败，已回滚: " + e.getMessage(), e);
        }

        if (sourceFeedbackId != null) {
            feedbackService.markApproved(sourceFeedbackId, createdBy, pair.getId());
        }
        log.info("[GoldenPair] 新建 id={} milvusId={} norm={}", pair.getId(), pair.getMilvusId(), norm);
        return pair.getId();
    }

    // ─────────────────────────────────────────────
    // 修改 / 删除
    // ─────────────────────────────────────────────
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, String question, String answer, Integer enabled, String category, String tags) {
        QaGoldenPair p = goldenMapper.selectById(id);
        if (p == null) throw new IllegalArgumentException("Golden Pair 不存在");

        boolean questionChanged = question != null && !question.equals(p.getQuestion());
        if (questionChanged) {
            p.setQuestion(question.trim());
            p.setQuestionNorm(normalize(question));
        }
        if (answer != null) p.setStandardAnswer(answer);
        if (enabled != null) p.setEnabled(enabled);
        if (category != null) p.setCategory(category);
        if (tags != null) p.setTags(tags);
        goldenMapper.updateById(p);

        if (questionChanged) {
            // 问题改了 → 重新 embed + 重写 Milvus
            List<Float> emb = embed(p.getQuestion());
            goldenMilvus.upsert(p.getId(), p.getMilvusId(), emb);
        } else if (Integer.valueOf(0).equals(enabled)) {
            // 禁用 → 从 Milvus 删除（不再参与命中）
            goldenMilvus.delete(p.getMilvusId());
        } else if (Integer.valueOf(1).equals(enabled)) {
            // 重新启用 → 需要重写 Milvus（之前可能被删除过）
            List<Float> emb = embed(p.getQuestion());
            goldenMilvus.upsert(p.getId(), p.getMilvusId(), emb);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        QaGoldenPair p = goldenMapper.selectById(id);
        if (p == null) return;
        goldenMilvus.delete(p.getMilvusId());
        goldenMapper.deleteById(id);
        log.info("[GoldenPair] 删除 id={}", id);
    }

    // ─────────────────────────────────────────────
    // 命中检索（Agent 在 chat 流程最前端调用）
    // ─────────────────────────────────────────────
    public record HitOutcome(QaGoldenPair pair, float score) {}

    /**
     * 搜索是否命中已有 golden pair。
     * 流程：
     *  1. 先做归一化精确匹配（最快路径）
     *  2. 失败则走 Milvus 向量相似度搜索
     *  3. 命中后 hit_count + 1
     *
     * @return null 表示未命中
     */
    public HitOutcome searchHit(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) return null;
        String norm = normalize(userQuestion);

        // 1) 精确匹配（归一化 question_norm 命中）
        QaGoldenPair exact = goldenMapper.selectOne(new LambdaQueryWrapper<QaGoldenPair>()
                .eq(QaGoldenPair::getQuestionNorm, norm)
                .eq(QaGoldenPair::getEnabled, 1)
                .last("LIMIT 1"));
        if (exact != null) {
            incrementHit(exact.getId());
            log.info("[GoldenPair] 精确命中 id={} norm={}", exact.getId(), norm);
            return new HitOutcome(exact, 1.0f);
        }

        // 2) 向量相似度搜索
        List<Float> emb;
        try {
            emb = embed(userQuestion);
        } catch (Exception e) {
            log.warn("[GoldenPair] embed 失败，跳过 Milvus 搜索: {}", e.getMessage());
            return null;
        }

        GoldenPairMilvusService.HitResult hit = goldenMilvus.searchTopOne(emb);
        if (hit == null || hit.pairId() == null) return null;

        QaGoldenPair p = goldenMapper.selectById(hit.pairId());
        if (p == null || Integer.valueOf(0).equals(p.getEnabled())) return null;

        incrementHit(p.getId());
        log.info("[GoldenPair] 向量命中 id={} score={}", p.getId(), hit.score());
        return new HitOutcome(p, hit.score());
    }

    private void incrementHit(Long id) {
        try {
            QaGoldenPair patch = new QaGoldenPair();
            patch.setId(id);
            patch.setLastHitAt(LocalDateTime.now());
            goldenMapper.updateById(patch);
            // hit_count 单独 update（避免空字段覆盖）
            goldenMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<QaGoldenPair>()
                            .setSql("hit_count = hit_count + 1")
                            .eq(QaGoldenPair::getId, id));
        } catch (Exception e) {
            log.warn("[GoldenPair] 计数失败 id={}: {}", id, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────
    // 查询
    // ─────────────────────────────────────────────
    public QaGoldenPair getById(Long id) { return goldenMapper.selectById(id); }

    public IPage<QaGoldenPair> page(int current, int size, String keyword, Integer enabled) {
        Page<QaGoldenPair> page = new Page<>(current, size);
        return goldenMapper.selectPage(page, new LambdaQueryWrapper<QaGoldenPair>()
                .and(keyword != null && !keyword.isBlank(),
                        w -> w.like(QaGoldenPair::getQuestion, keyword)
                              .or().like(QaGoldenPair::getStandardAnswer, keyword))
                .eq(enabled != null, QaGoldenPair::getEnabled, enabled)
                .orderByDesc(QaGoldenPair::getHitCount)
                .orderByDesc(QaGoldenPair::getCreateTime));
    }

    public Long total() { return goldenMapper.selectCount(null); }

    public Long totalHits() {
        List<QaGoldenPair> list = goldenMapper.selectList(null);
        long sum = 0;
        for (QaGoldenPair p : list) if (p.getHitCount() != null) sum += p.getHitCount();
        return sum;
    }

    // ─────────────────────────────────────────────
    // 工具
    // ─────────────────────────────────────────────

    /** 归一化：去前后空格 / 全转小写 / 去除常见标点 / 多空白合并 */
    public static String normalize(String q) {
        if (q == null) return "";
        String s = q.trim().toLowerCase();
        // 去除标点
        s = s.replaceAll("[\\p{Punct}\\p{IsPunctuation}]", " ");
        // 多空白合并
        s = s.replaceAll("\\s+", " ").trim();
        if (s.length() > 500) s = s.substring(0, 500);
        return s;
    }

    private List<Float> embed(String text) {
        float[] arr = embeddingModel.embed(text);
        List<Float> out = new ArrayList<>(arr.length);
        for (float f : arr) out.add(f);
        return out;
    }

    @SuppressWarnings("unused")
    private String safeJson(Object o) {
        try { return JSON.toJSONString(o); } catch (Exception e) { return null; }
    }
}
