-- =====================================================================
-- 任务 7 补强 · kb_acl 增加部门级授权
--
-- 之前：一条 ACL 必须绑定 position_id（职位级）
-- 现在：position_id 或 department_id 二选一（业务约定 · 不在 DB 层强约束）
--   - 部门级支持向下继承（含所有子部门用户）
--   - 职位级精确到单一角色
--
-- 运行: mysql -uroot -p docmind < sql/kb-acl-department-migration.sql
-- =====================================================================

ALTER TABLE `kb_acl`
    ADD COLUMN `department_id` BIGINT NULL
        COMMENT '部门级授权 · NULL 表示用 position_id'
        AFTER `position_id`,
    -- 业务约束：position_id 和 department_id 不能同时为空，也不能同时非空
    -- 此约束放在应用层校验（DB CHECK 跨版本兼容性差）
    MODIFY COLUMN `position_id` BIGINT NULL COMMENT '职位级授权 · NULL 表示用 department_id';

-- 替换原唯一约束：之前是 (kb_id, position_id) UK；现在按 subject 类型区分
ALTER TABLE `kb_acl` DROP INDEX `uk_kb_pos`;

-- 新增双索引（不强 UK，应用层保证幂等）
ALTER TABLE `kb_acl`
    ADD KEY `idx_dept` (`department_id`);
