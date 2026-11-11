-- =====================================================================
-- qa_message · 加 retrieval_log 列
--
-- 用于持久化 RAG 检索日志（Query 改写 / 多路召回数 / RRF / 重排 / 命中状态）
-- 之前只在 SSE done 事件里发给前端，没存 DB 导致刷新或切会话后看不到
-- 运行: mysql -uroot -p docmind < sql/retrieval-log-migration.sql
--
-- 幂等：用 information_schema 判断是否已加列
-- =====================================================================

SET @col_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME   = 'qa_message'
      AND COLUMN_NAME  = 'retrieval_log'
);

SET @ddl := IF(
    @col_exists = 0,
    'ALTER TABLE `qa_message` ADD COLUMN `retrieval_log` JSON NULL COMMENT ''RAG 检索过程日志（query 改写/召回数/重排/命中）'' AFTER `reflection_log`',
    'SELECT ''[skip] retrieval_log 列已存在，无需迁移'' AS msg'
);

PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
