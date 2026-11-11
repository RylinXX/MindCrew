# 设计契约 · 客服中心（含 SSO 单点登录 / 钉钉渠道）

> 本文是「客服中心」新模块的唯一实现契约。后端与前端按本文并行开发，字段名、路由、状态机必须一致。
> 若实现中必须调整，先改本文，再改代码，并在交付说明里记录。

## 1. 目标

在现有 Agentic RAG 能力之上新增「客服中心」：

1. 对外客服页面：`/cs`（无需登录，可匿名；也支持从客户自有系统 SSO 免登录跳转）
2. 对外页面可配置：标题、欢迎语、Logo、主题色、快捷问题、工作时间、离线提示、知识库范围、转人工关键词、满意度开关
3. 客服工作台：会话列表 / 认领 / 回复 / 转接 / 关闭 / 满意度 / 统计
4. SSO：客户自有系统签名换取 MindCrew JWT + 一次性 ticket，302 跳转我们的客服页
5. 钉钉机器人渠道：配置 + 回调，消息走同一套 RAG 回答
6. 全部后台配置变更走 `@Audited` 审计

复用现有能力：`MindCrewAgent`（Agentic RAG 主链路）、`SysUser`、`AuditLog`、`RedisTemplate`、`AesCryptoUtils`。

## 2. 数据模型（`sql/cs-center-schema.sql`）

> 放置包：实体 `com.simon.MindCrew.entity.Cs*`，Mapper 必须在 `com.simon.MindCrew.mapper`（`@MapperScan` 只扫这个包）。
> 所有表 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci`，带 `create_time/update_time/deleted` 的表要加 `@TableLogic`/`@TableField(fill=...)`。
> schema 文件要能独立执行，且带种子数据（默认页面配置、默认钉钉占位配置）。

### 2.1 cs_config（单行，id=1，页面配置）

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| id | BIGINT PK | 1 | 固定单行 |
| page_title | VARCHAR(100) | '"MindCrew 智能客服"' | 页面标题 |
| page_subtitle | VARCHAR(200) | | 副标题 |
| welcome_text | VARCHAR(1000) | | 首屏欢迎语 |
| logo_url | VARCHAR(500) | | Logo |
| theme_color | VARCHAR(20) | '#3D5AFE' | 主题色 |
| quick_questions | JSON | '["怎么配置权限？"]' | 快捷问题数组 |
| work_hours | VARCHAR(100) | '9:00-18:00' | 工作时间文案 |
| offline_tip | VARCHAR(500) | | 非工作时间提示 |
| ai_enabled | TINYINT | 1 | AI 先答 |
| handoff_keywords | JSON | '["转人工","人工客服"]' | 命中即转人工 |
| kb_ids | JSON | NULL | 客服 AI 检索范围；NULL=全部可见 |
| sso_enabled | TINYINT | 0 | 是否开放 SSO |
| sso_mode | VARCHAR(20) | 'internal' | internal=跳我们的 /cs；external=跳客户客服页 |
| external_cs_url | VARCHAR(500) | NULL | external 模式目标地址 |
| satisfaction_enabled | TINYINT | 1 | 满意度 |
| show_brand | TINYINT | 1 | 显示 MindCrew 品牌 |
| greeting_agent_name | VARCHAR(50) | '小梦' | AI 坐席名 |
| create_time / update_time | DATETIME | CURRENT_TIMESTAMP | |

### 2.2 cs_sso_app（对接的外部系统）

`id, app_name VARCHAR(100), app_id VARCHAR(64) UNIQUE, app_secret VARCHAR(255)（AES 加密存储）, enabled TINYINT, auto_create_user TINYINT DEFAULT 1, default_role VARCHAR(20) DEFAULT 'user', remark VARCHAR(500), create_time, update_time, deleted`

### 2.3 cs_sso_user（外部账号 → 本地账号映射）

`id, app_id VARCHAR(64), external_user_id VARCHAR(128), external_name VARCHAR(100), user_id BIGINT, last_login DATETIME, create_time`
唯一键 `UNIQUE KEY uk_app_ext (app_id, external_user_id)`。

### 2.4 cs_session（客服会话）

`id, session_no VARCHAR(64) UNIQUE, visitor_key VARCHAR(128), visitor_name VARCHAR(100), source_app_id VARCHAR(64), channel VARCHAR(20) DEFAULT 'web'（web/dingtalk/api/embed）, kb_ids JSON, conversation_id BIGINT（qa_conversation.id，AI 用）, status VARCHAR(20) DEFAULT 'bot'（bot/human/closed）, assignee_id BIGINT, message_count INT DEFAULT 0, last_message VARCHAR(500), last_active DATETIME, sso_user_id BIGINT, rating TINYINT, rating_note VARCHAR(500), ip VARCHAR(64), user_agent VARCHAR(500), create_time, update_time, deleted`
索引：`idx_last_active(last_active DESC)`、`idx_status(status)`、`idx_assignee(assignee_id)`。

### 2.5 cs_message（客服消息）

`id, session_id BIGINT, role VARCHAR(20)（user/ai/agent/system）, content TEXT, sources JSON, retrieval_log JSON, sender_id BIGINT（人工坐席 user_id）, create_time`
索引：`idx_session_id(session_id, id)`。

### 2.6 cs_dingtalk_channel（钉钉渠道）

`id, name VARCHAR(100), app_key VARCHAR(100), app_secret VARCHAR(255)（AES）, sign_secret VARCHAR(255)（AES）, robot_code VARCHAR(100), webhook_url VARCHAR(500), enabled TINYINT DEFAULT 0, kb_ids JSON, welcome_text VARCHAR(500), create_time, update_time, deleted`

## 3. 后端类清单（包与职责）

| 文件 | 职责 |
|---|---|
| `entity/CsConfig.java` … `entity/CsDingTalkChannel.java`（6 个实体） | MyBatis-Plus 实体 |
| `mapper/CsConfigMapper.java` … `mapper/CsDingTalkChannelMapper.java`（6 个） | 继承 `BaseMapper<T>` |
| `service/CsTokenService.java` | 访客 token 签发/校验（HMAC-SHA256，密钥 `@Value("${jwt.secret}")`）；ticket 存 Redis |
| `service/CsConfigService.java` | 读/写 `cs_config`；对外只暴露可用字段；内部缓存可省（首版直接查库） |
| `service/CsSsoService.java` | 验签、upsert `cs_sso_user` + `sys_user`、签发 JWT、生成/消费 ticket |
| `service/CsSessionService.java` | 建会话、列表、认领、回复、转接、关闭、满意度、统计 |
| `service/CsChatService.java` | 调 `MindCrewAgent` 产出 AI 回答（异步线程池），落 `cs_message`；转人工判定 |
| `service/CsDingTalkService.java` | 配置读写、验签、按 sender 建会话并同步回答 |
| `controller/CsPublicController.java` | `/api/cs/public/**` 公开接口 |
| `controller/CsSsoController.java` | `/api/cs/sso/**` |
| `controller/CsConsoleController.java` | `/api/cs/console/**`（登录即可） |
| `controller/CsAdminController.java` | `/api/cs/admin/**`（`@PreAuthorize("hasRole('ADMIN')")`） |
| `controller/CsDingTalkController.java` | `/api/cs/dingtalk/callback`（公开） |

`SecurityConfig.WHITE_LIST` 追加：`"/api/cs/public/**", "/api/cs/sso/**", "/api/cs/dingtalk/**"`。console/admin 保持登录鉴权。

### 3.1 身份与 AI 调用约定

- **客服 AI 使用专用机器人账号**：用户名 `cs_robot`；服务启动/首用时按用户名查找，不存在则插入 `sys_user`（role=`admin`，status=1，随机 BCrypt 密码，nickname=`智能客服`）。用它的 `id` 调 `MindCrewAgent.execute(String.valueOf(botId), conversationId, question, kbIds, emitter)`。
  - 之所以用 admin 机器人：Agent 内部会做 KB ACL 求交集，客服用例需要按 `cs_config.kb_ids` 精确限定范围；机器人有全量权限，交集结果就是配置范围。
  - `cs_session.conversation_id` 首次 AI 回答后回写，用于多轮上下文。
- **答案收集**：`new CollectingEmitter()`（`com.simon.MindCrew.controller.CollectingEmitter`），`execute` 返回后 `getAnswer()` / `getSources()`。
- **异步**：`CsChatService` 内 `ExecutorService fixed(4)`；POST 消息立即返回 `pending=true`，前端轮询。
- **转人工**：命中 `handoff_keywords` 或 `ai_enabled=0` 时，status→`human`，写一条 `system` 消息「已为您转接人工客服」。
- **AI 失败兜底**：异常时写 `ai` 消息「抱歉，我暂时无法回答，已通知人工客服」，并把 status 置 `human`。

### 3.2 访客 token

`token = base64url(sessionNo + "." + expireEpochSeconds) + "." + hex(HMAC_SHA256(jwt.secret, sessionNo + "." + expireEpochSeconds))`
有效期 7 天。公开接口（除创建会话）必须在请求头 `X-CS-Token` 带 token，校验失败返回 401 业务码（`Result.error(401, "访客凭证无效")`）。

### 3.3 SSO 协议（写进交付文档给客户）

- 签名：`sign = hex(HMAC_SHA256(appSecret, appId + "\n" + externalUserId + "\n" + ts + "\n" + nonce))`
- `ts` 为秒级时间戳，允许 ±300s；`nonce` 通过 Redis `cs:sso:nonce:{nonce}`（TTL 600s）防重放。
- 入口：`GET /api/cs/sso/entry?appId&userId&name&ts&nonce&sign&redirect=/cs`
  - 校验 `cs_config.sso_enabled=1` 且 `cs_sso_app.enabled=1`
  - 失败 → 302 `redirect + "?cs_error=invalid_sign"`（`redirect` 只允许站内相对路径，防开放重定向）
  - 成功 → Redis 写 ticket（key `cs:sso:ticket:{ticket}`，TTL 300s，value JSON `{userId, appId, externalUserId, name}`），302 `redirect + "?ticket=" + ticket`
  - `sso_mode=external` 时：302 `external_cs_url + "?mindcrewToken=" + JWT + "&externalUserId=" + externalUserId`（JWT 24h）
- 换取登录态：`POST /api/cs/sso/exchange {ticket}` → `{token, user:{id,username,nickname,role}, appId}`；ticket 一次性删除。
- 自动建号：`username = "sso_" + appId + "_" + externalUserId` 截断 50 字符，冲突时加 `_2`；`role = default_role`。

## 4. 接口契约

统一 `Result<T>`。分页返回结构用 `{records, total, current, size}`（可直接返回 MyBatis-Plus `Page`，前端按 `records` 取）。

### 4.1 公开接口 `/api/cs/public`

| 方法 | 路径 | 入参 | 出参 data |
|---|---|---|---|
| GET | `/config` | - | `{pageTitle,pageSubtitle,welcomeText,logoUrl,themeColor,quickQuestions[],workHours,offlineTip,aiEnabled,satisfactionEnabled,showBrand,greetingAgentName,ssoEnabled,ssoMode,online}` |
| POST | `/session` | `{visitorKey?,visitorName?,channel?}` | `{sessionId,sessionNo,visitorToken,status,welcome,quickQuestions[]}` |
| GET | `/messages` | `?sessionNo&sinceId=0`（头 `X-CS-Token`） | `{status, assigneeName, messages:[{id,role,content,sources,createTime}]}` |
| POST | `/message` | `{sessionNo,content}`（头 `X-CS-Token`） | `{messageId,pending,status}` |
| POST | `/handoff` | `{sessionNo}` | `{status:"human"}` |
| POST | `/rating` | `{sessionNo,rating:1-5,note?}` | `{rating}` |
| POST | `/close` | `{sessionNo}` | `{status:"closed"}` |

`sources` 为 JSON 数组字符串（沿用 Agent sources 结构），前端可 JSON.parse 展示「答案溯源」。

### 4.2 SSO `/api/cs/sso`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/entry` | 见 3.3，302 |
| POST | `/exchange` | `{ticket}` → `{token,user,appId}` |
| GET | `/meta?appId=` | `{enabled,appName}`（供对接方自检） |

### 4.3 工作台 `/api/cs/console`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/sessions?current&size&status&channel&keyword` | `Page<CsSession>`；补 `assigneeName` |
| GET | `/sessions/{id}` | `{session, assigneeName, messages[]}` |
| POST | `/sessions/{id}/claim` | 认领：status→human，assignee=当前用户 |
| POST | `/sessions/{id}/reply` | `{content}`，写 role=agent 消息 |
| POST | `/sessions/{id}/transfer` | `{assigneeId}` |
| POST | `/sessions/{id}/close` | status→closed |
| GET | `/stats?days=7` | `{sessions,aiSessions,handoffCount,handoffRate,avgRating,satisfactionRate,statusDist:[{status,count}],trend:[{date,count}]}` |

### 4.4 管理 `/api/cs/admin`

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/config` | 全量配置 |
| PUT | `/config` | 保存配置（`@Audited(action="cs.config.update")`） |
| GET | `/sso-apps` | 列表（secret 打码） |
| POST | `/sso-apps` | 新建（返回一次明文 secret） |
| PUT | `/sso-apps/{id}` | 更新 |
| DELETE | `/sso-apps/{id}` | 逻辑删除 |
| POST | `/sso-apps/{id}/rotate-secret` | 重新生成 secret（返回一次明文） |
| GET | `/channels/dingtalk` | 钉钉配置（secret 打码） |
| PUT | `/channels/dingtalk` | 保存钉钉配置 |
| POST | `/channels/dingtalk/test` | `{content}` 走 RAG 生成回复，仅返回文本，不真发（离线可验证） |
| GET | `/overview` | `{config, ssoAppCount, sessionTotal, todaySessions}` |

### 4.5 钉钉回调 `/api/cs/dingtalk`

`POST /api/cs/dingtalk/callback?channelId=1&sign=&timestamp=`，body 为钉钉 outgoing 机器人 JSON（取 `senderStaffId`/`senderNick`/`text.content`）。
校验：配置了 `sign_secret` 时校验 `sign = base64(HMAC_SHA256(sign_secret, timestamp + "\n" + app_secret))`，失败 401。
响应：`{"msgtype":"markdown","markdown":{"title":"MindCrew 智能客服","text":"..."}}`。

## 5. 前端契约

| 路由 | 组件 | 权限 |
|---|---|---|
| `/cs` | `views/cs/CsPortalView.vue`（独立布局，不套 MainLayout） | 公开 |
| `/cs-console` | `views/cs/CsConsoleView.vue` | 登录 |
| `/cs-settings` | `views/admin/CsSettingsView.vue` | 管理员 |
| `/retrieval-analytics` | `views/admin/RetrievalAnalyticsView.vue` | 管理员（另一任务） |
| `/deliverables` | `views/office/DeliverableView.vue` | 登录（另一任务） |

API 模块：`src/api/cs.ts`（公共 + 工作台 + 管理 + SSO）、`src/api/retrieval.ts`、`src/api/office.ts`。
Axios baseURL 已是 `/api`，路径写 `/cs/public/config` 形式。
公开页使用 `axios` 实例同样注入 localStorage token（SSO 后可用）；匿名请求带 `X-CS-Token`。

`/cs` 交互：
1. `?ticket=` → 调 `/cs/sso/exchange` → 存 token 到 localStorage → 清 URL query
2. 拉 `/config` 渲染（主题色、欢迎语、快捷问题、工作时间）
3. 无会话则 `POST /session`（visitorKey 存 localStorage）→ 拿到 sessionNo + visitorToken（存 sessionStorage）
4. 发消息 → `POST /message` → 每 1.2s 轮询 `/messages?sinceId` 直到出现新消息（最多 90s）
5. 输入命中转人工 → 自动调 `/handoff`；人工回复通过轮询到达
6. 关闭时满意度弹窗 → `/rating`

`/cs-console`：左侧会话列表（状态筛选/搜索，30s 自动刷新），右侧对话流 + 回复框 + 认领/转接/关闭按钮，顶部统计卡。
`/cs-settings`：Tab「页面配置 / SSO 对接 / 钉钉渠道」；页面配置含实时预览（右侧手机框）。

## 6. 验收清单

- [ ] `sql/cs-center-schema.sql` 在空库可独立执行，种子配置可读
- [ ] 匿名访客：进入 /cs → 建会话 → 提问 → 收到 AI 回答（含来源）→ 转人工 → 工作台认领并回复 → 访客轮询看到 → 评分
- [ ] SSO：按 3.3 用 shell 生成签名 → 浏览器访问 entry → 302 带 ticket → exchange 拿到 JWT → /cs 免登录
- [ ] 配置改动后公开页立即生效（无需重启）
- [ ] 管理接口非 admin 访问 403；SSO 非法签名 302 cs_error；重放 nonce 被拒
- [ ] 钉钉回调返回 markdown 结构；签名错误 401
- [ ] 配置变更出现在审计日志（action 前缀 `cs.`）
