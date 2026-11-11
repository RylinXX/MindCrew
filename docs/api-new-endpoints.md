# 新增接口总表（客服中心 / 检索分析 / 数字员工交付物 / AI 问数）

> 统一返回 `Result<T>`：`{code, message, data}`，成功 `code=200`。项目惯例：业务错误仍是 HTTP 200 + `body.code`（仅钉钉回调的 401/403 使用对应 HTTP 状态码），前端以 `body.code` 判断。
> 分页统一返回 `{records, total, current, size}`。
> 前端 Axios `baseURL=/api`，下文路径均为完整路径。
> 权限列说明：**公开** = 安全白名单免登录；**登录** = `authenticated`；**ADMIN** = `hasRole('ADMIN')`；**ADMIN/AUDITOR** = `hasAnyRole('ADMIN','AUDITOR')`。

## 一、客服中心 · 公开接口

前缀 `/api/cs/public`，安全白名单，免登录。除 `GET /config` 与创建会话外，均需请求头 `X-CS-Token`（访客 token，7 天有效），无效返回业务码 401「访客凭证无效」。

| 方法 | 路径 | 权限 | 入参 | 出参 data 摘要 |
|---|---|---|---|---|
| GET | `/api/cs/public/config` | 公开 | - | `{pageTitle, pageSubtitle, welcomeText, logoUrl, themeColor, quickQuestions[], workHours, offlineTip, aiEnabled, satisfactionEnabled, showBrand, greetingAgentName, ssoEnabled, ssoMode, online}` |
| POST | `/api/cs/public/session` | 公开 | `{visitorKey?, visitorName?, channel?}` | `{sessionId, sessionNo, visitorToken, status, welcome, quickQuestions[]}` |
| GET | `/api/cs/public/messages` | 公开 | `?sessionNo&sinceId=0`（头 `X-CS-Token`） | `{status, assigneeName, messages:[{id, role, content, sources, createTime}]}` |
| POST | `/api/cs/public/message` | 公开 | `{sessionNo, content}`（头 `X-CS-Token`） | `{messageId, pending, status}` |
| POST | `/api/cs/public/handoff` | 公开 | `{sessionNo}` | `{status:"human"}` |
| POST | `/api/cs/public/rating` | 公开 | `{sessionNo, rating:1-5, note?}` | `{rating}` |
| POST | `/api/cs/public/close` | 公开 | `{sessionNo}` | `{status:"closed"}` |

要点：

- `sources` 为 JSON 数组字符串，前端 `JSON.parse` 后展示答案溯源。
- 发消息后 AI 异步生成，接口立即返回 `pending=true`，前端轮询 `/messages?sinceId`。
- 命中转人工关键词或 `aiEnabled=0` 时会话转 `human`，并写一条 `system` 消息。

## 二、客服中心 · SSO 接口

前缀 `/api/cs/sso`，安全白名单。

| 方法 | 路径 | 权限 | 入参 | 出参 |
|---|---|---|---|---|
| GET | `/api/cs/sso/entry` | 公开 | `?appId&userId&name&ts&nonce&sign&redirect=/cs` | 302 到 `redirect?ticket=...`；失败 302 `redirect?cs_error=invalid_sign`；`external` 模式 302 到 `external_cs_url?mindcrewToken=...&externalUserId=...` |
| POST | `/api/cs/sso/exchange` | 公开 | `{ticket}` | `{token, user:{id, username, nickname, role}, appId}`；ticket 一次性消费 |
| GET | `/api/cs/sso/meta` | 公开 | `?appId=` | `{enabled, appName}`（供对接方自检） |

签名：`hex(HMAC_SHA256(appSecret, appId + "\n" + userId + "\n" + ts + "\n" + nonce))`；`ts` 秒级 ±300s；`nonce` 600s 防重放。

## 三、客服中心 · 坐席工作台

前缀 `/api/cs/console`，登录即可访问。

| 方法 | 路径 | 权限 | 入参 | 出参 data 摘要 |
|---|---|---|---|---|
| GET | `/api/cs/console/sessions` | 登录 | `?current&size&status&channel&keyword` | `Page<CsSession>`（`{records,total,current,size}`），每条补 `assigneeName` |
| GET | `/api/cs/console/sessions/{id}` | 登录 | - | `{session, assigneeName, messages[]}` |
| POST | `/api/cs/console/sessions/{id}/claim` | 登录 | - | 认领结果（状态 `human`，坐席为当前用户） |
| POST | `/api/cs/console/sessions/{id}/reply` | 登录 | `{content}` | 写入 `role=agent` 消息后的结果 |
| POST | `/api/cs/console/sessions/{id}/transfer` | 登录 | `{assigneeId}` | 转接结果 |
| POST | `/api/cs/console/sessions/{id}/close` | 登录 | - | 状态 `closed` |
| GET | `/api/cs/console/stats` | 登录 | `?days=7` | `{sessions, aiSessions, handoffCount, handoffRate, avgRating, satisfactionRate, statusDist:[{status,count}], trend:[{date,count}]}` |

## 四、客服中心 · 管理接口

前缀 `/api/cs/admin`，`@PreAuthorize("hasRole('ADMIN')")`；配置与渠道写操作 `@Audited(action="cs.*")`（新建 SSO 应用 / 重置密钥除外，避免明文密钥入审计）。

| 方法 | 路径 | 权限 | 入参 | 出参 data 摘要 |
|---|---|---|---|---|
| GET | `/api/cs/admin/config` | ADMIN | - | 全量页面配置 |
| PUT | `/api/cs/admin/config` | ADMIN | `CsConfig` 字段；`quickQuestions` / `handoffKeywords` / `kbIds` 传 JSON 数组字符串 | 保存后的配置；审计 `cs.config.update` |
| GET | `/api/cs/admin/sso-apps` | ADMIN | - | 应用列表（secret 打码） |
| POST | `/api/cs/admin/sso-apps` | ADMIN | `{appName, enabled?, autoCreateUser?, defaultRole?, remark?}`（`appId` 由服务端生成） | 新建结果，含**一次明文** `appSecret` |
| PUT | `/api/cs/admin/sso-apps/{id}` | ADMIN | 同上（不含 secret） | 更新结果 |
| DELETE | `/api/cs/admin/sso-apps/{id}` | ADMIN | - | 逻辑删除 |
| POST | `/api/cs/admin/sso-apps/{id}/rotate-secret` | ADMIN | - | 新 secret（**只返回一次明文**） |
| GET | `/api/cs/admin/channels/dingtalk` | ADMIN | - | 钉钉配置（secret 打码） |
| PUT | `/api/cs/admin/channels/dingtalk` | ADMIN | `{name, appKey, appSecret?, signSecret?, robotCode?, webhookUrl?, enabled?, kbIds?, welcomeText?}` | 保存结果 |
| POST | `/api/cs/admin/channels/dingtalk/test` | ADMIN | `{content}` | 仅返回 RAG 生成的回复文本（字符串），不真正发送 |
| GET | `/api/cs/admin/overview` | ADMIN | - | `{config, ssoAppCount, sessionTotal, todaySessions}` |

## 五、客服中心 · 钉钉回调

| 方法 | 路径 | 权限 | 入参 | 出参 |
|---|---|---|---|---|
| POST | `/api/cs/dingtalk/callback` | 公开（白名单） | query：`channelId`、`sign`、`timestamp`；body：钉钉 outgoing 机器人 JSON | `{"msgtype":"markdown","markdown":{"title":"MindCrew 智能客服","text":"..."}}`；验签失败 401 |

验签：配置了 `signSecret` 时校验 `sign = base64(HMAC_SHA256(signSecret, timestamp + "\n" + appSecret))`。

## 六、检索分析

前缀 `/api/v2/retrieval`，全部只读，类级 `@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")`。

| 方法 | 路径 | 权限 | 入参 | 出参 data 摘要 |
|---|---|---|---|---|
| GET | `/api/v2/retrieval/overview` | ADMIN/AUDITOR | `?days=30` | 20 项 KPI：`totalAnswers, withLog, hitCount, hitRate, zeroResultCount, zeroResultRate, fallbackCount, fallbackRate, rewriteCount, rewriteRate, avgVector, avgBm25, avgRrf, avgRerank, avgLatencyMs, positiveFeedback, negativeFeedback, satisfactionRate, totalCostCny, avgCostCny` |
| GET | `/api/v2/retrieval/trend` | ADMIN/AUDITOR | `?days=30` | `[{date, questions, hitRate, zeroResultCount, avgLatencyMs, fallbackCount}]`，按天补零升序 |
| GET | `/api/v2/retrieval/tools` | ADMIN/AUDITOR | `?days=30` | `[{tool, count, ratio}]`，按 count 降序；空工具记 `none` |
| GET | `/api/v2/retrieval/kbs` | ADMIN/AUDITOR | `?days=30` | `[{kbId, kbName, count, hitRate, avgRerank}]`，按 count 降序 |
| GET | `/api/v2/retrieval/low-quality` | ADMIN/AUDITOR | `?days=30&current=1&size=20&type=zero` | `{records:[{messageId, conversationId, question, rewrittenQuery, vectorResults, bm25Results, rrfCount, rerankTop, fallback, feedback, latencyMs, modelName, createTime}], total, current, size}` |
| GET | `/api/v2/retrieval/detail/{messageId}` | ADMIN/AUDITOR | 路径参数 | `{messageId, question, answer, retrievalLog, reflectionLog, agentTrace, sources, feedback, latencyMs}`；非 assistant 消息 400，不存在 404 |
| GET | `/api/v2/retrieval/low-quality/export` | ADMIN/AUDITOR | `?days=30&type=zero` | CSV 字节流（`text/csv;charset=UTF-8`，UTF-8 BOM，`low-quality-YYYYMMDD.csv`，最多 5000 行） |

`type` 取值：`zero`（默认）/ `fallback` / `negative`；未知值按 `zero` 处理。`days` 默认 30，实现侧钳制 1~365。

## 七、数字员工交付物

前缀 `/api/v2/office`，登录即可；Crew 交付物额外校验任务归属。

| 方法 | 路径 | 权限 | 入参 | 出参 |
|---|---|---|---|---|
| GET | `/api/v2/office/templates` | 登录 | - | `[{id, name, description, fields:[{key, label, required}]}]` |
| POST | `/api/v2/office/pptx` | 登录 | `{title, subtitle?, markdown}` | `application/vnd.openxmlformats-officedocument.presentationml.presentation` 字节流 |
| POST | `/api/v2/office/docx` | 登录 | `{title, markdown}` | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` 字节流 |
| POST | `/api/v2/office/contract` | 登录 | `{template, fields}` | DOCX 字节流；必填缺失/金额非法 400 |
| GET | `/api/v2/crew/tasks/{taskId}/deliverable` | 登录 + 归属校验 | `?format=pptx / docx / md` | 文件流；`md` 返回 `finalReport` 原文；非本人且非 admin → 403；报告为空 → 400 |

合同模板：`purchase`（采购合同）、`service`（服务合同）。下载响应头使用 RFC 5987 编码文件名，中文不乱码。

## 八、AI 问数

前缀 `/api/v2/data-qa`，登录即可，只读。

| 方法 | 路径 | 权限 | 入参 | 出参 data 摘要 |
|---|---|---|---|---|
| GET | `/api/v2/data-qa/metrics` | 登录 | - | `[{key, name, description, chartType, example}]` |
| POST | `/api/v2/data-qa/ask` | 登录 | `{question, days?}` | `{question, metric, metricName, interpreted, answer, columns[], rows[], chart:{type,xKey,yKey,seriesName}, elapsedMs}` |

- 指标白名单 9 个：`question_count`、`active_users`、`token_usage`、`cost_by_model`、`latency_trend`、`feedback_ratio`、`kb_ranking`、`top_queries`、`usage_by_day`。
- `days` 钳制 1~90（默认 7），`limit` 钳制 1~50（默认 10）。
- 不支持的问法返回业务码 400，并附指标列表；无数据时 `rows` 为空数组。

## 九、权限与白名单总览

| 范围 | 鉴权方式 |
|---|---|
| `/api/cs/public/**`、`/api/cs/sso/**`、`/api/cs/dingtalk/**` | 安全白名单，免登录；公开接口用访客 token 校验 |
| `/api/cs/console/**` | 登录 |
| `/api/cs/admin/**` | `hasRole('ADMIN')` |
| `/api/v2/retrieval/**` | `hasAnyRole('ADMIN','AUDITOR')` |
| `/api/v2/office/**`、`/api/v2/crew/tasks/*/deliverable` | 登录；交付物额外校验任务归属 |
| `/api/v2/data-qa/**` | 登录，只读 |

审计：客服中心配置保存、SSO 应用更新/删除、钉钉渠道保存记 `cs.*`；新建 SSO 应用与重置密钥因返回一次性明文密钥不落审计。检索分析与 AI 问数为读操作，不写审计。
