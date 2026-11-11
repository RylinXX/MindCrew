-- =====================================================================
-- 任务 7 · 职位独立知识库
--   sys_department  · 部门（树形组织架构）
--   sys_position    · 职位（业务角色，区别于 sys_user.role 系统角色）
--   sys_user        · 扩展字段 department_id + position_id
--   kb_acl          · 知识库 × 职位 的访问控制 (read/write/admin)
--   kb_knowledge_base · 扩展 visibility 字段
-- 运行: mysql -uroot -p docmind < sql/dept-position-acl-schema.sql
-- =====================================================================

-- ─────────────────────────────────────────────
-- 1) 部门表
-- ─────────────────────────────────────────────
DROP TABLE IF EXISTS `sys_department`;
CREATE TABLE `sys_department` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `name`        VARCHAR(60)  NOT NULL                COMMENT '部门名',
    `parent_id`   BIGINT       NULL                    COMMENT '父部门 ID（NULL 表示一级）',
    `description` VARCHAR(200) NULL,
    `sort_order`  INT          NOT NULL DEFAULT 100,
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`     TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='组织部门 · 树形';

-- 预置一级部门样例
INSERT INTO `sys_department` (`name`, `parent_id`, `sort_order`, `description`) VALUES
('总部',     NULL, 10, '公司总部 / 默认部门'),
('技术中心', NULL, 20, '产品研发与技术运维'),
('市场销售', NULL, 30, '市场推广与销售'),
('人事行政', NULL, 40, 'HR · 行政 · 法务'),
('财务',     NULL, 50, '财务部门');


-- ─────────────────────────────────────────────
-- 2) 职位表（业务角色，独立于 sys_user.role）
-- ─────────────────────────────────────────────
DROP TABLE IF EXISTS `sys_position`;
CREATE TABLE `sys_position` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `name`          VARCHAR(60)  NOT NULL                COMMENT '职位名',
    `code`          VARCHAR(40)  NOT NULL                COMMENT '英文 code · 唯一',
    `department_id` BIGINT       NULL                    COMMENT '默认所属部门（可空·跨部门职位）',
    `description`   VARCHAR(200) NULL                    COMMENT '职责说明',
    `level`         INT          NOT NULL DEFAULT 1      COMMENT '职级 1-10',
    `sort_order`    INT          NOT NULL DEFAULT 100,
    `enabled`       TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`       TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`),
    KEY `idx_dept` (`department_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='职位 · 业务角色';

-- 预置 7 个典型职位
INSERT INTO `sys_position` (`name`, `code`, `department_id`, `description`, `level`, `sort_order`) VALUES
('CEO',          'ceo',          1, '首席执行官',         10, 10),
('技术总监',     'tech_lead',    2, '技术中心负责人',       8, 20),
('Java 工程师',  'java_dev',     2, '后端 Java 开发',       3, 30),
('前端工程师',   'frontend_dev', 2, '前端 / Web 开发',      3, 40),
('销售经理',     'sales_mgr',    3, '销售部主管',           6, 50),
('HR 经理',     'hr_mgr',       4, 'HR 行政',              6, 60),
('财务专员',     'finance_staff', 5, '财务核算 · 报销审批',  3, 70);


-- ─────────────────────────────────────────────
-- 3) sys_user 扩展（不修改已有字段）
--    用 IF NOT EXISTS 类逻辑保险一点 · 这里 MySQL 不支持 ADD COLUMN IF NOT EXISTS
--    所以执行前确认表里没有 department_id / position_id 字段
-- ─────────────────────────────────────────────
ALTER TABLE `sys_user`
    ADD COLUMN `department_id` BIGINT NULL COMMENT '部门 ID（关联 sys_department）' AFTER `role`,
    ADD COLUMN `position_id`   BIGINT NULL COMMENT '职位 ID（关联 sys_position）'   AFTER `department_id`;

-- 默认把 admin 用户绑定到总部 / CEO 职位，让超级管理员能访问所有 KB
UPDATE `sys_user` SET `department_id` = 1, `position_id` = 1 WHERE `username` = 'admin';


-- ─────────────────────────────────────────────
-- 4) 知识库 ACL 表
-- ─────────────────────────────────────────────
DROP TABLE IF EXISTS `kb_acl`;
CREATE TABLE `kb_acl` (
    `id`          BIGINT      NOT NULL AUTO_INCREMENT,
    `kb_id`       BIGINT      NOT NULL                  COMMENT '关联 kb_knowledge_base.id',
    `position_id` BIGINT      NOT NULL                  COMMENT '关联 sys_position.id',
    `permission`  VARCHAR(10) NOT NULL DEFAULT 'read'   COMMENT 'read · 检索；write · 上传；admin · 删除/授权',
    `granted_by`  BIGINT      NULL                      COMMENT '授权人 user_id',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`     TINYINT(1)  NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_kb_pos` (`kb_id`, `position_id`),
    KEY `idx_kb`  (`kb_id`),
    KEY `idx_pos` (`position_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='知识库 × 职位 访问控制';


-- ─────────────────────────────────────────────
-- 5) kb_knowledge_base 增加 visibility 字段
--    public  · 所有人可读（兼容现有 KB 默认行为）
--    scoped  · 按 kb_acl 控制
--    private · 仅创建者可见
-- ─────────────────────────────────────────────
ALTER TABLE `kb_knowledge_base`
    ADD COLUMN `visibility` VARCHAR(20) NOT NULL DEFAULT 'public'
        COMMENT 'public · 所有人 / scoped · 按 ACL / private · 仅创建者';

-- 既有 KB 默认 public（不改变行为）· 客户后续按需收紧
