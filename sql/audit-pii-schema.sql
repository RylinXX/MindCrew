-- =====================================================================
-- 任务 12 · 审计日志 + PII 脱敏配置
--   audit_log     · 全量操作审计（合规必备）
--   pii_config    · PII 脱敏开关与策略配置（单行）
-- 运行: mysql -uroot -p docmind < sql/audit-pii-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `audit_log`;
CREATE TABLE `audit_log` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `user_id`       BIGINT       NULL                    COMMENT '操作人 user_id（系统自动 / 未登录场景为 NULL）',
    `username`      VARCHAR(60)  NULL                    COMMENT '操作人 username 冗余字段（用户后续被删也能查）',
    `action`        VARCHAR(60)  NOT NULL                COMMENT '动作 code · 如 user.login / kb.upload / kb.delete / acl.grant',
    `action_label`  VARCHAR(120) NULL                    COMMENT '动作中文描述',
    `target_type`   VARCHAR(40)  NULL                    COMMENT '目标类型 · 如 kb / user / api_key / golden_pair',
    `target_id`     VARCHAR(80)  NULL                    COMMENT '目标 ID',
    `target_name`   VARCHAR(200) NULL                    COMMENT '目标显示名 · 冗余便于日志可读',
    `status`        VARCHAR(20)  NOT NULL DEFAULT 'success' COMMENT 'success / failure',
    `detail_json`   JSON         NULL                    COMMENT '详细参数 / 响应（脱敏后）',
    `error_msg`     VARCHAR(500) NULL,
    `ip`            VARCHAR(60)  NULL,
    `user_agent`    VARCHAR(255) NULL,
    `latency_ms`    INT          NOT NULL DEFAULT 0,
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user`        (`user_id`, `created_at` DESC),
    KEY `idx_action`      (`action`,  `created_at` DESC),
    KEY `idx_target`      (`target_type`, `target_id`),
    KEY `idx_created_at`  (`created_at` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='审计日志 · 合规追溯';


DROP TABLE IF EXISTS `pii_config`;
CREATE TABLE `pii_config` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT,
    `enabled`          TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '全局总开关',
    `mask_phone`       TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '手机号脱敏',
    `mask_id_card`     TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '身份证号脱敏',
    `mask_bank_card`   TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '银行卡号脱敏',
    `mask_email`       TINYINT(1)  NOT NULL DEFAULT 0   COMMENT '邮箱脱敏（默认关，可能影响联系信息检索）',
    `mask_address`     TINYINT(1)  NOT NULL DEFAULT 0   COMMENT '地址脱敏（误判率高，默认关）',
    `apply_on_upload`  TINYINT(1)  NOT NULL DEFAULT 0   COMMENT '上传文档时入库前脱敏（不可逆）· 默认关',
    `apply_on_response` TINYINT(1) NOT NULL DEFAULT 1   COMMENT '问答响应时脱敏（DB 不动）· 默认开',
    `apply_on_audit`   TINYINT(1)  NOT NULL DEFAULT 1   COMMENT '写审计日志前脱敏 detail_json',
    `updated_by`       BIGINT      NULL,
    `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='PII 脱敏全局配置 · 单行';

-- 默认配置（首次启用时插入）
INSERT INTO `pii_config` (`enabled`, `mask_phone`, `mask_id_card`, `mask_bank_card`,
                           `mask_email`, `mask_address`, `apply_on_upload`, `apply_on_response`, `apply_on_audit`)
VALUES (1, 1, 1, 1, 0, 0, 0, 1, 1);
