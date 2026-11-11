-- =====================================================================
-- 任务 6 · 校正反哺闭环
--   qa_feedback     · 用户对 AI 答复的评分 / 纠正
--   qa_golden_pair  · 审核后的标准问答对（Milvus 中有对应向量）
-- 运行: mysql -uroot -p docmind < sql/feedback-golden-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `qa_feedback`;
CREATE TABLE `qa_feedback` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT,
    `message_id`          BIGINT       NOT NULL                   COMMENT '关联 qa_message.id（AI 回答消息）',
    `conversation_id`     BIGINT       NOT NULL                   COMMENT '关联 qa_conversation.id',
    `user_id`             BIGINT       NOT NULL                   COMMENT '提交反馈的用户',
    `rating`              VARCHAR(10)  NOT NULL                   COMMENT 'up · 赞 / down · 踩',
    `comment`             VARCHAR(500) NULL                       COMMENT '用户简短评论',
    `correction_text`     TEXT         NULL                       COMMENT '用户/审核员提供的标准答案',
    `correction_sources`  JSON         NULL                       COMMENT '来源引用 JSON（可空）',
    `status`              VARCHAR(20)  NOT NULL DEFAULT 'pending' COMMENT 'pending · 待审核 / approved · 已收录 / rejected · 已驳回',
    `reviewer_id`         BIGINT       NULL                       COMMENT '审核员用户 ID',
    `reviewer_note`       VARCHAR(500) NULL                       COMMENT '审核备注',
    `reviewed_at`         DATETIME     NULL,
    `golden_pair_id`      BIGINT       NULL                       COMMENT '审核通过后生成的 golden pair id',
    `create_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`             TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_message`      (`message_id`),
    KEY `idx_status`       (`status`),
    KEY `idx_user`         (`user_id`),
    KEY `idx_conversation` (`conversation_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='用户对 AI 答复的反馈 / 校正';


DROP TABLE IF EXISTS `qa_golden_pair`;
CREATE TABLE `qa_golden_pair` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `question`          TEXT         NOT NULL                   COMMENT '标准问题（用户原问题）',
    `question_norm`     VARCHAR(500) NOT NULL                   COMMENT '归一化后的问题 · 大小写/标点统一，用于快速精确匹配',
    `standard_answer`   TEXT         NOT NULL                   COMMENT '审核员认可的标准答案',
    `sources_json`      JSON         NULL                       COMMENT '引用来源 JSON [{"name":"xx","kbId":1,"chunkIdx":3}]',
    `milvus_id`         VARCHAR(64)  NOT NULL                   COMMENT 'Milvus 中对应向量主键',
    `source_feedback_id` BIGINT      NULL                       COMMENT '来源 qa_feedback.id（说明这条 golden 是从哪条反馈来的）',
    `category`          VARCHAR(40)  NULL                       COMMENT '分类标签（关联 kb_category.code）',
    `tags`              JSON         NULL                       COMMENT '标签数组',
    `enabled`           TINYINT(1)   NOT NULL DEFAULT 1         COMMENT '是否启用（禁用时不参与命中）',
    `hit_count`         INT          NOT NULL DEFAULT 0         COMMENT '被命中次数',
    `last_hit_at`       DATETIME     NULL                       COMMENT '上次命中时间',
    `created_by`        BIGINT       NOT NULL                   COMMENT '创建人 user_id',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question_norm` (`question_norm`),
    KEY `idx_milvus`     (`milvus_id`),
    KEY `idx_enabled`    (`enabled`),
    KEY `idx_hit`        (`hit_count` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='人工校正的标准问答对（"AI 越用越准"的核心数据）';

-- 给 sys_user 加 auditor 角色支持（如果原表 role 已是 varchar 就只需要在代码层校验）
-- 现有表已使用 varchar role，不动表结构，仅约定 role IN ('admin','auditor','user')
