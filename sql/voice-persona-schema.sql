-- =====================================================================
-- 任务 14 · 实时语音对话
--   voice_persona · 音色配置（CosyVoice 预置音色 + 后续自定义复刻）
-- 运行: mysql -uroot -p docmind < sql/voice-persona-schema.sql
-- =====================================================================

DROP TABLE IF EXISTS `voice_persona`;
CREATE TABLE `voice_persona` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `name`         VARCHAR(80)  NOT NULL                COMMENT '展示名（如「龙小淳 · 知性女声」）',
    `voice_id`     VARCHAR(80)  NOT NULL                COMMENT '厂商音色 ID（如 longxiaochun_v2）',
    `provider`     VARCHAR(40)  NOT NULL DEFAULT 'cosyvoice' COMMENT 'cosyvoice / volcengine / minimax / custom',
    `model`        VARCHAR(80)  NOT NULL DEFAULT 'cosyvoice-v2' COMMENT 'TTS 模型版本',
    `gender`       VARCHAR(10)  NULL                    COMMENT 'male / female / child / neutral',
    `language`     VARCHAR(20)  NULL DEFAULT 'zh-CN'    COMMENT '主要语言',
    `description`  VARCHAR(200) NULL                    COMMENT '风格描述',
    `tags`         VARCHAR(200) NULL                    COMMENT '标签（逗号分隔）',
    `sample_rate`  INT          NOT NULL DEFAULT 22050  COMMENT '生成音频采样率',
    `owner_user_id` BIGINT      NULL                    COMMENT '自定义音色归属用户（预置为 NULL）',
    `is_default`   TINYINT(1)   NOT NULL DEFAULT 0      COMMENT '系统默认音色（1 个）',
    `enabled`      TINYINT(1)   NOT NULL DEFAULT 1,
    `sort_order`   INT          NOT NULL DEFAULT 100,
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`      TINYINT(1)   NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_voice` (`provider`, `voice_id`),
    KEY `idx_owner` (`owner_user_id`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='音色配置 · CosyVoice 预置 + 自定义复刻';

-- 预置 CosyVoice v2 常用音色（阿里云百炼官方）
INSERT INTO `voice_persona`
    (`name`, `voice_id`, `provider`, `model`, `gender`, `language`, `description`, `tags`, `is_default`, `sort_order`) VALUES
('龙小淳 · 知性女声',  'longxiaochun_v2', 'cosyvoice', 'cosyvoice-v2', 'female', 'zh-CN', '清晰知性，适合知识库讲解', '客服,讲解', 1, 10),
('龙小诚 · 沉稳男声',  'longxiaocheng_v2','cosyvoice', 'cosyvoice-v2', 'male',   'zh-CN', '沉稳低音，适合正式问答', '正式,助理', 0, 20),
('龙小白 · 活力女声',  'longxiaobai_v2', 'cosyvoice', 'cosyvoice-v2', 'female', 'zh-CN', '年轻活力，适合互动对话', '互动,年轻', 0, 30),
('龙华 · 温和男声',    'longhua_v2',     'cosyvoice', 'cosyvoice-v2', 'male',   'zh-CN', '温和友好，适合教练模式', '温和,教练', 0, 40),
('龙婉 · 温柔女声',    'longwan_v2',     'cosyvoice', 'cosyvoice-v2', 'female', 'zh-CN', '温柔自然，适合长时段陪伴', '温柔,陪伴', 0, 50),
('龙铁牛 · 大叔音',    'longlaotie_v2',  'cosyvoice', 'cosyvoice-v2', 'male',   'zh-CN', '北方腔大叔音，幽默接地气', '幽默,大叔', 0, 60);
