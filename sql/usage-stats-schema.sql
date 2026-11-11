-- =====================================================================
-- 任务 13 · 用量统计与历史对话分析
--   model_pricing  · 模型计费配置（input/output 单价）
--   usage_daily    · 用户每日用量聚合（一行 = 一个 user 一天）
-- 运行: mysql -uroot -p docmind < sql/usage-stats-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `model_pricing`;
CREATE TABLE `model_pricing` (
    `id`               BIGINT      NOT NULL AUTO_INCREMENT,
    `model_name`       VARCHAR(80) NOT NULL                COMMENT '模型名 · 如 qwen-plus / qwen-vl-max / text-embedding-v3',
    `category`         VARCHAR(20) NOT NULL                COMMENT 'chat · vision · embedding · asr · ocr',
    `input_price_per_1k`  DECIMAL(10,6) NOT NULL DEFAULT 0.000000  COMMENT '每 1K input token 价格（人民币元）',
    `output_price_per_1k` DECIMAL(10,6) NOT NULL DEFAULT 0.000000  COMMENT '每 1K output token 价格',
    `unit_price`       DECIMAL(10,6) NULL                  COMMENT '按调用次数计费的单价（如 OCR 一次 0.05 元）',
    `description`      VARCHAR(200) NULL,
    `enabled`          TINYINT(1)  NOT NULL DEFAULT 1,
    `create_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_model` (`model_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型计费配置';

-- 预置主流模型价格（2026 年 6 月阿里云百炼价格 · 单位：元/1K tokens）
INSERT INTO `model_pricing` (`model_name`, `category`, `input_price_per_1k`, `output_price_per_1k`, `description`) VALUES
('qwen-turbo',          'chat',      0.000300, 0.000600, '通义千问 Turbo · 最便宜'),
('qwen-plus',           'chat',      0.000800, 0.002000, '通义千问 Plus · 主用'),
('qwen-max',            'chat',      0.020000, 0.060000, '通义千问 Max · 最强'),
('qwen-vl-max',         'vision',    0.020000, 0.020000, '通义千问 VL · 图片识别'),
('qwen-vl-plus',        'vision',    0.008000, 0.008000, '通义千问 VL Plus · 便宜版'),
('text-embedding-v3',   'embedding', 0.000500, 0.000000, 'Embedding · 仅 input 计费'),
('deepseek-chat',       'chat',      0.000270, 0.001100, 'DeepSeek-V3'),
('gpt-4o',              'chat',      0.018000, 0.072000, 'OpenAI GPT-4o'),
('gpt-4o-mini',         'chat',      0.000540, 0.002160, 'OpenAI GPT-4o-mini');

-- 按次计费类（不分 input/output）
INSERT INTO `model_pricing` (`model_name`, `category`, `unit_price`, `description`) VALUES
('paraformer-v2',       'asr',       0.000200, 'ASR · 每秒 0.0002 元（按音频时长）'),
('gte-rerank',          'rerank',    0.000050, 'Rerank · 每次调用 0.00005 元');


DROP TABLE IF EXISTS `usage_daily`;
CREATE TABLE `usage_daily` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT      NOT NULL,
    `stat_date`       DATE        NOT NULL                COMMENT '统计日期 YYYY-MM-DD',
    `chat_count`      INT         NOT NULL DEFAULT 0      COMMENT '对话次数（assistant 消息数）',
    `input_tokens`    BIGINT      NOT NULL DEFAULT 0      COMMENT '当日总 input tokens',
    `output_tokens`   BIGINT      NOT NULL DEFAULT 0,
    `embedding_tokens` BIGINT     NOT NULL DEFAULT 0,
    `vision_calls`    INT         NOT NULL DEFAULT 0      COMMENT 'VL 图片识别次数',
    `asr_seconds`     INT         NOT NULL DEFAULT 0      COMMENT 'ASR 音频秒数累计',
    `cost_cny`        DECIMAL(12,4) NOT NULL DEFAULT 0.0000 COMMENT '当日累计成本（人民币）',
    `golden_hit_count` INT        NOT NULL DEFAULT 0      COMMENT '命中 Golden Pair 次数',
    `create_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_date` (`user_id`, `stat_date`),
    KEY `idx_date` (`stat_date` DESC),
    KEY `idx_cost` (`cost_cny` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户每日用量聚合';


-- qa_message 加 tokens / cost / model 字段（如果还没加）
-- 注意：ALTER TABLE 不能 IF NOT EXISTS，重复执行会报错，按需删除
ALTER TABLE `qa_message`
    ADD COLUMN `model_name`    VARCHAR(80) NULL COMMENT '本条消息用的模型' AFTER `feedback`,
    ADD COLUMN `input_tokens`  INT NOT NULL DEFAULT 0 AFTER `model_name`,
    ADD COLUMN `output_tokens` INT NOT NULL DEFAULT 0 AFTER `input_tokens`,
    ADD COLUMN `cost_cny`      DECIMAL(10,6) NOT NULL DEFAULT 0.000000 AFTER `output_tokens`,
    ADD COLUMN `latency_ms`    INT NOT NULL DEFAULT 0 AFTER `cost_cny`;
