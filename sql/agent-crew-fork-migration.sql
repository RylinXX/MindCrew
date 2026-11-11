-- =====================================================================
-- Time-Travel 调试支持：为 agent_task 增加 fork 关系字段
-- 运行: mysql -uroot -p <你的库名> < sql/agent-crew-fork-migration.sql
-- =====================================================================

ALTER TABLE `agent_task`
    ADD COLUMN `parent_task_id`     BIGINT      NULL COMMENT 'Fork 的原任务 ID（NULL 表示原始任务）' AFTER `conversation_id`,
    ADD COLUMN `forked_from_step`   INT         NULL COMMENT 'Fork 起点的步骤序号'                    AFTER `parent_task_id`,
    ADD COLUMN `fork_edit_summary`  VARCHAR(200) NULL COMMENT '用户在 Fork 时的编辑说明'              AFTER `forked_from_step`,
    ADD INDEX `idx_parent_task` (`parent_task_id`);
