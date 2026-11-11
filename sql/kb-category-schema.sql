-- =====================================================================
-- AI 自动分类 · 字典表 + kb_knowledge_base 字段扩展
-- 运行: mysql -uroot -p docmind < sql/kb-category-schema.sql
-- =====================================================================

-- ─────────────────────────────────────────────
-- 分类字典表（管理员可维护）
-- 注：kb_knowledge_base.category 仍保留 varchar 字段（按 code 写入），
-- 不做强 FK 约束以减少迁移风险。
-- ─────────────────────────────────────────────
DROP TABLE IF EXISTS `kb_category`;
CREATE TABLE `kb_category` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `code`        VARCHAR(40)  NOT NULL COMMENT '英文 code，用于 kb_knowledge_base.category 字段',
    `name`        VARCHAR(40)  NOT NULL COMMENT '中文展示名',
    `parent_id`   BIGINT       NULL     COMMENT '父分类 ID（NULL 表示一级）',
    `description` VARCHAR(200) NULL     COMMENT 'LLM 分类提示用 — 越具体越准确',
    `icon`        VARCHAR(30)  NULL     COMMENT '前端图标 / emoji，可空',
    `color`       VARCHAR(20)  NULL     COMMENT '前端徽标色，hex',
    `sort_order`  INT          NOT NULL DEFAULT 100,
    `enabled`     TINYINT(1)   NOT NULL DEFAULT 1,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`     TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='知识库文档分类字典';

INSERT INTO `kb_category` (`code`, `name`, `description`, `icon`, `color`, `sort_order`) VALUES
('hr',       '人事',  '招聘、入离职、考勤、薪资、绩效、员工手册',                      '👤', '#F472B6', 10),
('finance',  '财务',  '发票、报销、合同金额、预算、对账单、税务',                       '💰', '#F59E0B', 20),
('tech',     '技术',  '产品架构、接口文档、代码规范、运维手册、技术方案',               '⚙️', '#3D5AFE', 30),
('product',  '产品',  '需求文档、PRD、产品说明、用户手册、版本说明',                    '📦', '#0EA5E9', 40),
('legal',    '法务',  '合同、协议、合规、隐私政策、许可证、法律意见书',                 '⚖️', '#7C3AED', 50),
('training', '培训',  '员工培训资料、课程、考试题、新员工指引',                          '🎓', '#10B981', 60),
('customer', '客户',  '客户档案、销售记录、售后工单、客户反馈',                          '🤝', '#EC4899', 70),
('other',    '其他',  '未明确归类或跨类别的内容',                                       '📁', '#64748B', 999);

-- ─────────────────────────────────────────────
-- kb_knowledge_base 字段扩展
-- ─────────────────────────────────────────────
ALTER TABLE `kb_knowledge_base`
    ADD COLUMN `tags` JSON NULL COMMENT 'LLM 提取的标签数组，如 ["合同","2024","客户A"]'             AFTER `category`,
    ADD COLUMN `summary` TEXT NULL COMMENT 'LLM 生成的 100-200 字摘要'                              AFTER `tags`,
    ADD COLUMN `category_user_set` TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否用户手动指定（1=锁定，AI 不再覆盖）' AFTER `summary`;

-- 历史数据：已存在的 category 视为系统初始值，category_user_set 默认 0 即可
-- （让 AI 可以在重新分类操作时覆盖那些占位值）
