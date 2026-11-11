-- =====================================================================
-- 任务 9 · 教练模式
--   coach_session  · 一次练习会话（含 KB 范围/难度/进度/总分）
--   coach_question · 单道题（题干/类型/选项/标准答案/来源 chunk）
--   coach_answer   · 用户作答（含 LLM 评分/反馈/推荐复习章节）
-- 运行: mysql -uroot -p docmind < sql/coach-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `coach_answer`;
DROP TABLE IF EXISTS `coach_question`;
DROP TABLE IF EXISTS `coach_session`;

CREATE TABLE `coach_session` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT       NOT NULL,
    `kb_ids`          JSON         NULL                       COMMENT '本 session 的 KB 范围（数组，留空 = 全量可访问 KB）',
    `kb_scope_label`  VARCHAR(200) NULL                       COMMENT '范围摘要（前端展示用 · 如「合同审查 / 财务制度」）',
    `difficulty`      VARCHAR(10)  NOT NULL DEFAULT 'medium'  COMMENT 'easy · medium · hard',
    `question_total`  INT          NOT NULL DEFAULT 10        COMMENT '本 session 计划题数',
    `question_done`   INT          NOT NULL DEFAULT 0         COMMENT '已答题数',
    `correct_count`   INT          NOT NULL DEFAULT 0         COMMENT '答对题数（score≥80）',
    `total_score`     INT          NOT NULL DEFAULT 0         COMMENT '累计分（满分=question_done*100）',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'active'  COMMENT 'active · finished · abandoned',
    `start_at`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `end_at`          DATETIME     NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `start_at` DESC),
    KEY `idx_status`    (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='教练模式 · 练习会话';

CREATE TABLE `coach_question` (
    `id`                 BIGINT       NOT NULL AUTO_INCREMENT,
    `session_id`         BIGINT       NOT NULL,
    `seq`                INT          NOT NULL                   COMMENT '该 session 内顺序号 1,2,3...',
    `question`           TEXT         NOT NULL,
    `question_type`      VARCHAR(20)  NOT NULL DEFAULT 'short_answer'  COMMENT 'short_answer · multiple_choice · true_false',
    `options`            JSON         NULL                       COMMENT '选择题选项（["A. xx","B. xx"]）',
    `expected_answer`    TEXT         NOT NULL                   COMMENT '标准答案',
    `explanation`        TEXT         NULL                       COMMENT '出题时附带的讲解',
    `source_chunk_id`    BIGINT       NULL                       COMMENT '题目来源 chunk id',
    `source_kb_id`       BIGINT       NULL                       COMMENT '题目来源 KB id',
    `source_kb_name`     VARCHAR(200) NULL                       COMMENT '来源 KB 名称（冗余 · 删 KB 后仍能显示）',
    `difficulty`         VARCHAR(10)  NOT NULL DEFAULT 'medium',
    `create_time`        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_session_seq` (`session_id`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='教练模式 · 单道题';

CREATE TABLE `coach_answer` (
    `id`                  BIGINT      NOT NULL AUTO_INCREMENT,
    `question_id`         BIGINT      NOT NULL,
    `session_id`          BIGINT      NOT NULL,
    `user_id`             BIGINT      NOT NULL,
    `user_answer`         TEXT        NULL,
    `score`               INT         NOT NULL DEFAULT 0          COMMENT '0-100',
    `judgment`            VARCHAR(20) NOT NULL DEFAULT 'wrong'    COMMENT 'correct · partial · wrong',
    `feedback`            TEXT        NULL                        COMMENT 'LLM 反馈话术',
    `recommend_chunk_ids` JSON        NULL                        COMMENT '推荐复习的 chunk ids',
    `answer_at`           DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_question` (`question_id`),
    KEY `idx_session`      (`session_id`),
    KEY `idx_user_time`    (`user_id`, `answer_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='教练模式 · 用户作答记录';
