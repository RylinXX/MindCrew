-- =====================================================================
-- 任务 13.5 · qa_conversation 加敏感标记 4 列
--   is_flagged / flag_note / flagged_by / flagged_at
-- 主管 / 管理员对 KB 问答中"涉密 / 越权 / 失实"等对话做标记，用于后续审计
-- 运行: mysql -uroot -p docmind < sql/conversation-flag-migration.sql
--
-- 幂等：用 information_schema 判断每个列是否已加
-- =====================================================================

-- ── is_flagged ───────────────────────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND COLUMN_NAME  = 'is_flagged');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `qa_conversation` ADD COLUMN `is_flagged` TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否标记敏感（主管标）''',
    'SELECT ''[skip] is_flagged 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── flag_note ───────────────────────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND COLUMN_NAME  = 'flag_note');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `qa_conversation` ADD COLUMN `flag_note` VARCHAR(500) NULL COMMENT ''标记备注''',
    'SELECT ''[skip] flag_note 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── flagged_by ───────────────────────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND COLUMN_NAME  = 'flagged_by');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `qa_conversation` ADD COLUMN `flagged_by` BIGINT NULL COMMENT ''标记人 user_id''',
    'SELECT ''[skip] flagged_by 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── flagged_at ───────────────────────────────────────────
SET @col := (SELECT COUNT(*) FROM information_schema.COLUMNS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND COLUMN_NAME  = 'flagged_at');
SET @ddl := IF(@col = 0,
    'ALTER TABLE `qa_conversation` ADD COLUMN `flagged_at` DATETIME NULL COMMENT ''标记时间''',
    'SELECT ''[skip] flagged_at 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── 检索索引：按用户+时间倒序、按敏感筛选 ─────────────────────
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_conversation'
               AND INDEX_NAME   = 'idx_flagged');
SET @ddl := IF(@idx = 0,
    'CREATE INDEX `idx_flagged` ON `qa_conversation`(`is_flagged`, `flagged_at` DESC)',
    'SELECT ''[skip] idx_flagged 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;

-- ── qa_message.content 加全文索引，支撑关键词搜索 ────────────
-- 注意：MySQL 8.0 InnoDB 全文索引对中文需要 ngram parser
SET @idx := (SELECT COUNT(*) FROM information_schema.STATISTICS
             WHERE TABLE_SCHEMA = DATABASE()
               AND TABLE_NAME   = 'qa_message'
               AND INDEX_NAME   = 'idx_content_ft');
SET @ddl := IF(@idx = 0,
    'ALTER TABLE `qa_message` ADD FULLTEXT INDEX `idx_content_ft`(`content`) WITH PARSER ngram',
    'SELECT ''[skip] idx_content_ft 已存在'' AS msg');
PREPARE s FROM @ddl; EXECUTE s; DEALLOCATE PREPARE s;
