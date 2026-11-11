-- =====================================================================
-- DocMind Multi-Agent Research Crew — Schema
-- 运行: mysql -uroot -p docmind < sql/agent-crew-schema.sql
-- =====================================================================

USE docmind;
SET NAMES utf8mb4;

-- ---------------------------------------------------------------------
-- agent_task: 多 Agent 协作任务主表
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_task`;
CREATE TABLE `agent_task` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `user_id`         BIGINT       NOT NULL                COMMENT '发起用户ID',
    `conversation_id` BIGINT       NULL                    COMMENT '关联会话ID（可空）',
    `query`           TEXT         NOT NULL                COMMENT '原始用户问题',
    `kb_ids`          VARCHAR(200) NULL                    COMMENT '检索知识库范围 JSON',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
                          COMMENT 'PENDING|PLANNING|RESEARCHING|WRITING|REVIEWING|REVISING|COMPLETED|FAILED',
    `current_role`    VARCHAR(20)  NULL                    COMMENT '当前活跃 Agent',
    `plan_json`       TEXT         NULL                    COMMENT 'Planner 输出（子任务列表）',
    `final_report`    LONGTEXT     NULL                    COMMENT '最终报告 Markdown',
    `review_score`    DECIMAL(3,2) NULL                    COMMENT 'Critic 评分 0~1',
    `revision_count`  INT          NOT NULL DEFAULT 0      COMMENT '已重写轮次',
    `total_steps`     INT          NOT NULL DEFAULT 0      COMMENT '总步骤数',
    `total_tokens`    INT          NOT NULL DEFAULT 0      COMMENT '总 token 估算',
    `elapsed_ms`      BIGINT       NOT NULL DEFAULT 0      COMMENT '总耗时(ms)',
    `error_msg`       TEXT         NULL                    COMMENT '失败原因',
    `start_time`      DATETIME     NULL                    COMMENT '开始时间',
    `end_time`        DATETIME     NULL                    COMMENT '结束时间',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    INDEX `idx_user_id`     (`user_id`),
    INDEX `idx_status`      (`status`),
    INDEX `idx_create_time` (`create_time` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Multi-Agent 协作任务主表';

-- ---------------------------------------------------------------------
-- agent_step: 每个 Agent 的执行步骤详情
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS `agent_step`;
CREATE TABLE `agent_step` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `task_id`      BIGINT       NOT NULL                COMMENT '关联 agent_task.id',
    `step_index`   INT          NOT NULL                COMMENT '步骤序号（任务内自增）',
    `agent_role`   VARCHAR(20)  NOT NULL                COMMENT 'PLANNER|RESEARCHER|WRITER|CRITIC',
    `step_name`    VARCHAR(120) NOT NULL                COMMENT '步骤名称（任务分解/调研子主题/撰写报告/质量评审）',
    `subtask`      VARCHAR(500) NULL                    COMMENT 'Researcher 的子任务问题',
    `input`        TEXT         NULL                    COMMENT '输入摘要',
    `output`       LONGTEXT     NULL                    COMMENT '输出（JSON 或文本）',
    `status`       VARCHAR(20)  NOT NULL DEFAULT 'RUNNING'
                       COMMENT 'RUNNING|DONE|FAILED|SKIPPED',
    `elapsed_ms`   BIGINT       NOT NULL DEFAULT 0,
    `tokens`       INT          NOT NULL DEFAULT 0,
    `error_msg`    TEXT         NULL,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_task_id`    (`task_id`),
    INDEX `idx_task_index` (`task_id`, `step_index`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Multi-Agent 步骤记录表（支持完整回放）';
