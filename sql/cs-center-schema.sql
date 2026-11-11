-- =====================================================================
-- 客服中心（含 SSO 单点登录 / 钉钉渠道）
--   cs_config            · 对外页面配置（单行 id=1）
--   cs_sso_app           · 对接的外部系统（appSecret AES 加密存储）
--   cs_sso_user          · 外部账号 → 本地 sys_user 映射
--   cs_session           · 客服会话（web / dingtalk / api / embed）
--   cs_message           · 客服消息（user / ai / agent / system）
--   cs_dingtalk_channel  · 钉钉机器人渠道（secret AES 加密存储）
-- 运行: mysql -uroot -p docmind < sql/cs-center-schema.sql
-- 说明: 无外键约束，DROP 顺序按「子表 → 父表」；可独立执行
-- =====================================================================

SET NAMES utf8mb4;

DROP TABLE IF EXISTS `cs_message`;
DROP TABLE IF EXISTS `cs_session`;
DROP TABLE IF EXISTS `cs_sso_user`;
DROP TABLE IF EXISTS `cs_sso_app`;
DROP TABLE IF EXISTS `cs_dingtalk_channel`;
DROP TABLE IF EXISTS `cs_config`;

-- ---------------------------------------------------------------------
-- 2.1 cs_config · 页面配置（固定单行 id=1）
-- ---------------------------------------------------------------------
CREATE TABLE `cs_config` (
    `id`                   BIGINT       NOT NULL                         COMMENT '固定单行 1',
    `page_title`           VARCHAR(100) NOT NULL DEFAULT 'MindCrew 智能客服' COMMENT '页面标题',
    `page_subtitle`        VARCHAR(200) NULL                           COMMENT '副标题',
    `welcome_text`         VARCHAR(1000) NULL                          COMMENT '首屏欢迎语',
    `logo_url`             VARCHAR(500) NULL                           COMMENT 'Logo',
    `theme_color`          VARCHAR(20)  NOT NULL DEFAULT '#3D5AFE'     COMMENT '主题色',
    `quick_questions`      JSON         NULL                           COMMENT '快捷问题数组（["怎么配置权限？"]）',
    `work_hours`           VARCHAR(100) NULL DEFAULT '9:00-18:00'      COMMENT '工作时间文案',
    `offline_tip`          VARCHAR(500) NULL                           COMMENT '非工作时间提示',
    `ai_enabled`           TINYINT      NOT NULL DEFAULT 1             COMMENT 'AI 先答 1开 0关',
    `handoff_keywords`     JSON         NULL                           COMMENT '命中即转人工的关键词数组',
    `kb_ids`               JSON         NULL                           COMMENT '客服 AI 检索范围；NULL=全部可见',
    `sso_enabled`          TINYINT      NOT NULL DEFAULT 0             COMMENT '是否开放 SSO',
    `sso_mode`             VARCHAR(20)  NOT NULL DEFAULT 'internal'    COMMENT 'internal=跳 /cs；external=跳客户客服页',
    `external_cs_url`      VARCHAR(500) NULL                           COMMENT 'external 模式目标地址',
    `satisfaction_enabled` TINYINT      NOT NULL DEFAULT 1             COMMENT '满意度开关',
    `show_brand`           TINYINT      NOT NULL DEFAULT 1             COMMENT '显示 MindCrew 品牌',
    `greeting_agent_name`  VARCHAR(50)  NOT NULL DEFAULT '小梦'         COMMENT 'AI 坐席名',
    `create_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='客服中心 · 对外页面配置（单行）';

-- ---------------------------------------------------------------------
-- 2.2 cs_sso_app · 对接的外部系统
-- ---------------------------------------------------------------------
CREATE TABLE `cs_sso_app` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `app_name`         VARCHAR(100) NOT NULL                         COMMENT '应用名称',
    `app_id`           VARCHAR(64)  NOT NULL                         COMMENT '外部系统标识（唯一）',
    `app_secret`       VARCHAR(255) NOT NULL                         COMMENT '签名密钥（AES 加密存储）',
    `enabled`          TINYINT      NOT NULL DEFAULT 1               COMMENT '是否启用',
    `auto_create_user` TINYINT      NOT NULL DEFAULT 1               COMMENT '首次登录是否自动建本地账号',
    `default_role`     VARCHAR(20)  NOT NULL DEFAULT 'user'          COMMENT '自动建号默认角色',
    `remark`           VARCHAR(500) NULL                             COMMENT '备注',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`          TINYINT      NOT NULL DEFAULT 0               COMMENT '逻辑删除 0正常 1删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_id` (`app_id`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='客服中心 · SSO 对接外部系统';

-- ---------------------------------------------------------------------
-- 2.3 cs_sso_user · 外部账号 → 本地账号映射
-- ---------------------------------------------------------------------
CREATE TABLE `cs_sso_user` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `app_id`           VARCHAR(64)  NOT NULL                         COMMENT '外部系统标识',
    `external_user_id` VARCHAR(128) NOT NULL                         COMMENT '外部系统用户 ID',
    `external_name`    VARCHAR(100) NULL                             COMMENT '外部系统用户名称',
    `user_id`          BIGINT       NULL                             COMMENT '本地 sys_user.id',
    `last_login`       DATETIME     NULL                             COMMENT '最近一次 SSO 登录时间',
    `create_time`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_app_ext` (`app_id`, `external_user_id`),
    KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='客服中心 · SSO 外部账号映射';

-- ---------------------------------------------------------------------
-- 2.4 cs_session · 客服会话
-- ---------------------------------------------------------------------
CREATE TABLE `cs_session` (
    `id`              BIGINT       NOT NULL AUTO_INCREMENT,
    `session_no`      VARCHAR(64)  NOT NULL                          COMMENT '会话编号（对外暴露）',
    `visitor_key`     VARCHAR(128) NULL                              COMMENT '访客标识（本地生成 / 钉钉 sender）',
    `visitor_name`    VARCHAR(100) NULL                              COMMENT '访客名称',
    `source_app_id`   VARCHAR(64)  NULL                              COMMENT 'SSO appId（来源外部系统）',
    `channel`         VARCHAR(20)  NOT NULL DEFAULT 'web'            COMMENT 'web / dingtalk / api / embed',
    `kb_ids`          JSON         NULL                              COMMENT '本会话 AI 检索范围',
    `conversation_id` BIGINT       NULL                              COMMENT 'qa_conversation.id（AI 多轮上下文）',
    `status`          VARCHAR(20)  NOT NULL DEFAULT 'bot'            COMMENT 'bot / human / closed',
    `assignee_id`     BIGINT       NULL                              COMMENT '人工坐席 sys_user.id',
    `message_count`   INT          NOT NULL DEFAULT 0                COMMENT '消息条数',
    `last_message`    VARCHAR(500) NULL                              COMMENT '最后一条消息摘要',
    `last_active`     DATETIME     NULL                              COMMENT '最后活跃时间',
    `sso_user_id`     BIGINT       NULL                              COMMENT '本地 sys_user.id（SSO 登录态）',
    `rating`          TINYINT      NULL                              COMMENT '满意度 1-5',
    `rating_note`     VARCHAR(500) NULL                              COMMENT '满意度备注',
    `ip`              VARCHAR(64)  NULL                              COMMENT '访客 IP',
    `user_agent`      VARCHAR(500) NULL                              COMMENT 'UA',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`         TINYINT      NOT NULL DEFAULT 0                COMMENT '逻辑删除 0正常 1删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_session_no` (`session_no`),
    KEY `idx_last_active` (`last_active` DESC),
    KEY `idx_status` (`status`),
    KEY `idx_assignee` (`assignee_id`),
    KEY `idx_visitor` (`visitor_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='客服中心 · 客服会话';

-- ---------------------------------------------------------------------
-- 2.5 cs_message · 客服消息
-- ---------------------------------------------------------------------
CREATE TABLE `cs_message` (
    `id`             BIGINT      NOT NULL AUTO_INCREMENT,
    `session_id`     BIGINT      NOT NULL                          COMMENT 'cs_session.id',
    `role`           VARCHAR(20) NOT NULL                          COMMENT 'user / ai / agent / system',
    `content`        TEXT        NULL                              COMMENT '消息内容（Markdown）',
    `sources`        JSON        NULL                              COMMENT 'AI 答案来源（JSON 数组字符串）',
    `retrieval_log`  JSON        NULL                              COMMENT 'RAG 检索过程日志',
    `sender_id`      BIGINT      NULL                              COMMENT '人工坐席 sys_user.id（role=agent 时）',
    `create_time`    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_session_id` (`session_id`, `id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='客服中心 · 客服消息';

-- ---------------------------------------------------------------------
-- 2.6 cs_dingtalk_channel · 钉钉机器人渠道
-- ---------------------------------------------------------------------
CREATE TABLE `cs_dingtalk_channel` (
    `id`           BIGINT       NOT NULL AUTO_INCREMENT,
    `name`         VARCHAR(100) NOT NULL                         COMMENT '渠道名称',
    `app_key`      VARCHAR(100) NULL                             COMMENT '钉钉 AppKey',
    `app_secret`   VARCHAR(255) NULL                             COMMENT '钉钉 AppSecret（AES 加密存储）',
    `sign_secret`  VARCHAR(255) NULL                             COMMENT '回调验签密钥（AES 加密存储）',
    `robot_code`   VARCHAR(100) NULL                             COMMENT '机器人编码',
    `webhook_url`  VARCHAR(500) NULL                             COMMENT '机器人 Webhook',
    `enabled`      TINYINT      NOT NULL DEFAULT 0               COMMENT '是否启用',
    `kb_ids`       JSON         NULL                             COMMENT '渠道 AI 检索范围',
    `welcome_text` VARCHAR(500) NULL                             COMMENT '欢迎语',
    `create_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `deleted`      TINYINT      NOT NULL DEFAULT 0               COMMENT '逻辑删除 0正常 1删除',
    PRIMARY KEY (`id`),
    KEY `idx_enabled` (`enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='客服中心 · 钉钉机器人渠道';

-- ---------------------------------------------------------------------
-- 种子数据
-- ---------------------------------------------------------------------
INSERT INTO `cs_config`
(`id`, `page_title`, `page_subtitle`, `welcome_text`, `logo_url`, `theme_color`, `quick_questions`,
 `work_hours`, `offline_tip`, `ai_enabled`, `handoff_keywords`, `kb_ids`, `sso_enabled`, `sso_mode`,
 `external_cs_url`, `satisfaction_enabled`, `show_brand`, `greeting_agent_name`)
VALUES
(1, 'MindCrew 智能客服', '基于企业知识库的智能问答', '你好，我是智能客服小梦，有什么可以帮你？', NULL, '#3D5AFE',
 '["怎么配置权限？","如何上传知识库？","支持哪些文件格式？"]',
 '9:00-18:00', '当前非工作时间，已为你转接人工客服，请留言。', 1,
 '["转人工","人工客服"]', NULL, 0, 'internal', NULL, 1, 1, '小梦');

INSERT INTO `cs_dingtalk_channel`
(`id`, `name`, `app_key`, `app_secret`, `sign_secret`, `robot_code`, `webhook_url`, `enabled`, `kb_ids`, `welcome_text`)
VALUES
(1, '默认钉钉机器人', NULL, NULL, NULL, NULL, NULL, 0, NULL, '你好，我是 MindCrew 智能客服，有什么可以帮你？');
