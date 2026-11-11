-- =====================================================================
-- 跨厂商模型 Provider 配置表
-- 运行: mysql -uroot -p docmind < sql/llm-provider-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `llm_provider`;
CREATE TABLE `llm_provider` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `name`            VARCHAR(50)  NOT NULL COMMENT '展示名（DashScope / DeepSeek / OpenAI / Ollama 本地 等）',
    `provider_type`   VARCHAR(30)  NOT NULL DEFAULT 'openai_compatible'
                          COMMENT '协议类型：目前都用 openai_compatible',
    `base_url`        VARCHAR(200) NOT NULL COMMENT 'API base URL，如 https://dashscope.aliyuncs.com/compatible-mode',
    `api_key_enc`     VARCHAR(500) NULL     COMMENT 'API Key 加密存储（AES）；本地模型可空',
    `chat_model`      VARCHAR(80)  NULL     COMMENT '对话模型名（qwen-plus / gpt-4o / deepseek-chat 等）',
    `embedding_model` VARCHAR(80)  NULL     COMMENT 'embedding 模型名（text-embedding-v3 / bge-m3 等）',
    `embedding_dim`   INT          NULL     COMMENT '向量维度（1024/1536/...），不填用 chat 端默认',
    `temperature`     DECIMAL(3,2) NOT NULL DEFAULT 0.70 COMMENT '默认温度',
    `description`     VARCHAR(300) NULL     COMMENT '管理员备注（如"自部署，单机 8 卡 A100"）',
    `is_active`       TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否为当前激活 provider（全局唯一）',
    `enabled`         TINYINT(1)   NOT NULL DEFAULT 1,
    `sort_order`      INT          NOT NULL DEFAULT 100,
    `last_test_at`    DATETIME     NULL     COMMENT '上次连通性测试时间',
    `last_test_ok`    TINYINT(1)   NULL     COMMENT '上次测试结果',
    `last_test_msg`   VARCHAR(500) NULL,
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='跨厂商 LLM Provider 配置';

-- ─────────────────────────────────────────────
-- 预置 5 个常见 Provider 模板（api_key 都是占位，需要管理员填）
-- ─────────────────────────────────────────────
INSERT INTO `llm_provider`
    (`name`, `provider_type`, `base_url`, `api_key_enc`, `chat_model`, `embedding_model`, `embedding_dim`, `temperature`, `description`, `is_active`, `sort_order`)
VALUES
(
    '阿里云百炼 · DashScope',
    'openai_compatible',
    'https://dashscope.aliyuncs.com/compatible-mode',
    '',
    'qwen-plus',
    'text-embedding-v3',
    1024,
    0.70,
    '国内首选：通义千问系列，中文最强，性价比高',
    1,                          -- 默认激活
    10
),
(
    'DeepSeek 官方',
    'openai_compatible',
    'https://api.deepseek.com',
    '',
    'deepseek-chat',
    NULL,                        -- DeepSeek 不提供 embedding
    NULL,
    0.70,
    'DeepSeek-V3 / R1，推理强，价格低；不提供 embedding（混搭其他厂商）',
    0,
    20
),
(
    'OpenAI 官方',
    'openai_compatible',
    'https://api.openai.com',
    '',
    'gpt-4o',
    'text-embedding-3-large',
    3072,
    0.70,
    '海外业务首选：GPT-4o / GPT-4o-mini，需要海外代理',
    0,
    30
),
(
    'Ollama 本地',
    'openai_compatible',
    'http://localhost:11434/v1',
    '',                          -- 本地无 key
    'qwen2.5:7b',
    'bge-m3',
    1024,
    0.70,
    '本地部署：单机 Ollama 服务，私有化场景；模型需 ollama pull',
    0,
    40
),
(
    'vLLM 自部署',
    'openai_compatible',
    'http://your-vllm-host:8000/v1',
    'EMPTY',                     -- vLLM 默认 key="EMPTY"
    'Qwen2.5-72B-Instruct',
    NULL,
    NULL,
    0.70,
    '高性能自部署：vLLM 推理引擎，OpenAI 协议；适合 70B+ 大模型',
    0,
    50
);
