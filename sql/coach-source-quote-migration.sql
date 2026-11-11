-- =====================================================================
-- 任务 9 教练模式 · 反幻觉强化迁移
-- 给 coach_question 加 source_quote 列，存放出题时引用的原文片段
-- 服务端会校验该片段必须是源 chunk 的真实子串，否则视为幻觉题，丢弃
-- 运行: mysql -uroot -p docmind < sql/coach-source-quote-migration.sql
--
-- 幂等：用 information_schema 判断是否已加列，避免 ERROR 1060 (Duplicate column)
-- =====================================================================

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 'coach_question'
      AND COLUMN_NAME  = 'source_quote'
);

SET @ddl := IF(
    @col_exists = 0,
    'ALTER TABLE `coach_question` ADD COLUMN `source_quote` TEXT NULL COMMENT ''出题时引用的原文片段（反幻觉证据）'' AFTER `source_kb_name`',
    'SELECT ''[skip] source_quote 列已存在，无需迁移'' AS msg'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
