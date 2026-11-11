-- =====================================================================
-- 任务 11 · API Key 对外开放（含 11.6 每 KB 独立 API）
--   api_key       · 对外 API key
--   api_call_log  · 调用日志（按 KB 维度可查）
-- 运行: mysql -uroot -p docmind < sql/api-key-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `api_key`;
CREATE TABLE `api_key` (
    `id`                BIGINT       NOT NULL AUTO_INCREMENT,
    `name`              VARCHAR(80)  NOT NULL                COMMENT '展示名（如"客户 X 接入"）',
    `key_prefix`        VARCHAR(20)  NOT NULL                COMMENT '前缀 mk_xxxxxxxx 用于列表展示',
    `key_hash`          VARCHAR(128) NOT NULL                COMMENT '完整 key 的 SHA-256（不存明文）',
    `allowed_kb_ids`    JSON         NOT NULL                COMMENT '可访问 KB id 数组 · 11.6 至少 1 个',
    `scope_type`        VARCHAR(20)  NOT NULL DEFAULT 'kb_scoped' COMMENT 'kb_scoped 每 KB · user_scoped 用户级',
    `monthly_quota`     INT          NOT NULL DEFAULT 10000  COMMENT '月调用次数上限',
    `rate_limit_qps`    INT          NOT NULL DEFAULT 10     COMMENT '每秒请求数（暂未启用 · 后续上 Redis 令牌桶）',
    `month_used`        INT          NOT NULL DEFAULT 0      COMMENT '当月已用次数',
    `month_key`         VARCHAR(7)   NULL                    COMMENT '统计月份 YYYY-MM，跨月自动归零',
    `total_calls`       BIGINT       NOT NULL DEFAULT 0      COMMENT '累计调用',
    `last_used_at`      DATETIME     NULL,
    `expire_at`         DATETIME     NULL                    COMMENT 'NULL 表示永不过期',
    `status`            VARCHAR(20)  NOT NULL DEFAULT 'active' COMMENT 'active / revoked / expired',
    `created_by`        BIGINT       NOT NULL,
    `description`       VARCHAR(300) NULL,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`           TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_hash` (`key_hash`),
    KEY `idx_status` (`status`),
    KEY `idx_creator` (`created_by`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对外 API Key · 11.6 支持 per-KB 独立';


DROP TABLE IF EXISTS `api_call_log`;
CREATE TABLE `api_call_log` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT,
    `key_id`         BIGINT       NOT NULL                COMMENT '关联 api_key.id',
    `kb_id`          BIGINT       NULL                    COMMENT '11.6 · 该调用作用于哪个 KB（用于 KB 维度查日志）',
    `api`            VARCHAR(40)  NOT NULL                COMMENT '/v3/chat · /v3/search · /v3/upload',
    `question`       VARCHAR(500) NULL                    COMMENT '问题截前 500 字（避免日志爆炸）',
    `status_code`    INT          NOT NULL                COMMENT 'HTTP 状态码',
    `input_tokens`   INT          NOT NULL DEFAULT 0,
    `output_tokens`  INT          NOT NULL DEFAULT 0,
    `cost_cny`       DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT '本次成本（人民币）',
    `latency_ms`     INT          NOT NULL DEFAULT 0,
    `ip`             VARCHAR(60)  NULL,
    `user_agent`     VARCHAR(255) NULL,
    `error_msg`      VARCHAR(500) NULL,
    `called_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_key`    (`key_id`,  `called_at` DESC),
    KEY `idx_kb`     (`kb_id`,   `called_at` DESC),
    KEY `idx_status` (`status_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='对外 API 调用日志';
