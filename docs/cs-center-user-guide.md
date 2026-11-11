# 客服中心使用手册（访客 / 坐席 / 管理员）

> 模块包含三块：对外客服页 `/cs`、坐席工作台 `/cs-console`、管理配置 `/cs-settings`。
> 后端接口前缀：`/api/cs/public`（公开）、`/api/cs/sso`（单点登录）、`/api/cs/console`（登录可用）、`/api/cs/admin`（仅管理员）、`/api/cs/dingtalk`（回调）。
> 部署前先执行 `sql/cs-center-schema.sql`（建表 + 默认页面配置 + 默认钉钉占位配置）。

## 一、访客视角

### 1.1 进入方式

| 方式 | 说明 |
|---|---|
| 直接访问 | 打开 `/cs`，无需登录，匿名可用 |
| SSO 免登录 | 由客户自有系统携带签名跳转 `/api/cs/sso/entry`，验签通过后 302 回 `/cs?ticket=...`，前端自动换取登录态后清除 URL 参数 |
| 钉钉群 | 群内 @机器人提问，消息经钉钉回调进入同一套 RAG 回答 |

### 1.2 页面内容

页面外观由管理员在「客服中心配置」里维护，访客看到的是：

| 元素 | 来源配置 |
|---|---|
| 页面标题 / 副标题 | `page_title` / `page_subtitle` |
| 首屏欢迎语 | `welcome_text` |
| Logo / 主题色 | `logo_url` / `theme_color` |
| 快捷问题按钮 | `quick_questions` |
| 工作时间 / 离线提示 | `work_hours` / `offline_tip` |
| AI 坐席名 | `greeting_agent_name`（默认「小梦」） |
| 满意度弹窗 | `satisfaction_enabled` |
| MindCrew 品牌 | `show_brand` |

### 1.3 提问与转人工

1. 首次进入自动创建会话，浏览器保存 `sessionNo` 与访客 token（token 有效期 7 天，请求头 `X-CS-Token`）。
2. 输入问题后，AI 先答（`ai_enabled=1` 时），回答下方展示来源，可展开「答案溯源」。
3. 发送消息后接口立即返回 `pending=true`，前端每 1.2 秒轮询一次，直到拿到新消息（最多等待约 90 秒）。
4. 命中转人工关键词（默认「转人工」「人工客服」），或管理员关闭了 AI 先答，会话状态从 `bot` 变为 `human`，并收到一条系统消息「已为您转接人工客服」。
5. 人工坐席回复后，访客通过轮询看到消息。
6. 会话结束后弹出满意度（1~5 星，可填备注）；重复提交会覆盖上一次评分（以后一次为准）。

### 1.4 非工作时间

当配置了 `work_hours` / `offline_tip` 时，非工作时段进入会看到离线提示；AI 仍可回答（除非 `ai_enabled=0`），需要人工时按转人工流程处理。

## 二、坐席视角

入口：`/cs-console`（登录后可见）。

### 2.1 会话列表

| 能力 | 说明 |
|---|---|
| 列表字段 | 会话编号、访客、渠道、状态、最后消息、最后活跃时间、当前坐席 |
| 筛选 | 状态（`bot / human / closed`）、渠道（`web / dingtalk / api / embed`）、关键词 |
| 刷新 | 页面每 30 秒自动刷新；也可手动刷新 |
| 排序 | 按最后活跃时间倒序 |

### 2.2 会话操作

| 操作 | 接口 | 效果 |
|---|---|---|
| 查看详情 | `GET /api/cs/console/sessions/{id}` | 返回会话、坐席名、完整消息流 |
| 认领 | `POST /api/cs/console/sessions/{id}/claim` | 状态转为 `human`，坐席设为当前用户 |
| 回复 | `POST /api/cs/console/sessions/{id}/reply` | 写入一条 `role=agent` 的消息 |
| 转接 | `POST /api/cs/console/sessions/{id}/transfer` | 指定其他坐席接手 |
| 关闭 | `POST /api/cs/console/sessions/{id}/close` | 状态转为 `closed` |

> 坐席不需要额外角色：只要登录就能进工作台；管理配置才要求 admin。

### 2.3 统计

工作台顶部展示 `GET /api/cs/console/stats?days=7` 的结果：

| 字段 | 含义 |
|---|---|
| `sessions` | 窗口内创建的会话总数 |
| `aiSessions` | 有 `conversationId`（AI 回答过）的会话数 |
| `handoffCount` | 转人工会话数（已分配坐席或状态为 `human`） |
| `handoffRate` | 转人工率 = `handoffCount / sessions × 100`，百分比保留 2 位小数 |
| `avgRating` | 已评分会话的平均分（1~5），保留 2 位小数；无评分记 0 |
| `satisfactionRate` | 满意率 = 评分 ≥4 的会话 / 已评分数 × 100，百分比保留 2 位小数 |
| `statusDist` | 状态分布 `[{status, count}]`，固定含 `bot / human / closed` 三项 |
| `trend` | 按创建日期统计的会话量 `[{date, count}]`，按天补零升序 |

`days` 默认 7，小于 1 按 7 处理，大于 90 按 90 处理。

## 三、管理员视角

入口：`/cs-settings`（仅 admin）。共三个 Tab：页面配置、SSO 对接、钉钉渠道；另有概览接口 `GET /api/cs/admin/overview` 返回 `{config, ssoAppCount, sessionTotal, todaySessions}`。

### 3.1 页面配置

| 字段 | 类型 | 默认 | 说明 |
|---|---|---|---|
| `pageTitle` | 文本 | MindCrew 智能客服 | 页面标题 |
| `pageSubtitle` | 文本 | — | 副标题 |
| `welcomeText` | 多行文本 | — | 首屏欢迎语 |
| `logoUrl` | URL | — | Logo 地址 |
| `themeColor` | 颜色 | `#3D5AFE` | 主题色 |
| `quickQuestions` | JSON 数组字符串 | 预置 3 条 | 快捷问题，`["怎么配置权限？"]` |
| `workHours` | 文本 | `9:00-18:00` | 工作时间文案 |
| `offlineTip` | 文本 | — | 非工作时间提示 |
| `aiEnabled` | 开关 | 开 | AI 是否先答 |
| `handoffKeywords` | JSON 数组字符串 | 转人工 / 人工客服 | 命中即转人工 |
| `kbIds` | JSON 数组字符串 | 空 | 客服 AI 检索范围；空 = 全部可见 |
| `ssoEnabled` | 开关 | 关 | 是否开放 SSO |
| `ssoMode` | 单选 | `internal` | `internal` 跳本站 `/cs`；`external` 跳客户客服页 |
| `externalCsUrl` | URL | 空 | `external` 模式目标地址 |
| `satisfactionEnabled` | 开关 | 开 | 是否启用满意度 |
| `showBrand` | 开关 | 开 | 是否显示 MindCrew 品牌 |
| `greetingAgentName` | 文本 | 小梦 | AI 坐席名 |

保存后公开页立即生效，无需重启；配置变更写入审计日志（action `cs.config.update`）。

### 3.2 SSO 对接应用

| 字段 | 说明 |
|---|---|
| `appName` | 应用名称，仅用于展示 |
| `appId` | 外部系统标识，唯一，由平台创建应用时生成，对接方使用 |
| `appSecret` | 签名密钥，AES 加密存储；新建与轮换时只返回一次明文 |
| `enabled` | 是否启用 |
| `autoCreateUser` | 首次登录是否自动创建本地账号 |
| `defaultRole` | 自动建号默认角色（默认 `user`） |
| `remark` | 备注 |

操作：列表（secret 打码）、新建、编辑、删除（逻辑删除）、轮换 secret（重新生成并返回一次明文）。

### 3.3 钉钉渠道

| 字段 | 说明 |
|---|---|
| `name` | 渠道名称 |
| `appKey` | 钉钉 AppKey |
| `appSecret` | 钉钉 AppSecret（AES 加密） |
| `signSecret` | 回调验签密钥（AES 加密） |
| `robotCode` | 机器人编码 |
| `webhookUrl` | 机器人 Webhook |
| `enabled` | 是否启用 |
| `kbIds` | 渠道 AI 检索范围 |
| `welcomeText` | 欢迎语 |

「测试」按钮调用 `POST /api/cs/admin/channels/dingtalk/test`，只走 RAG 生成回复文本、不真正发消息，便于离线验证配置。

## 四、SSO 对接步骤

### 4.1 时序

```
客户系统                         MindCrew
   │ 1. 计算签名                    │
   │ 2. 跳转 /api/cs/sso/entry ────▶│ 验签（appId / ts / nonce / sign）
   │                               │ 校验 cs_config.sso_enabled=1、cs_sso_app.enabled=1
   │                               │ Redis 写一次性 ticket（300s）
   │◀── 302 /cs?ticket=xxx ────────│
   │ 3. 前端取 ticket 调 exchange ─▶│ 消费 ticket（一次性）
   │◀── {token, user, appId} ──────│ 签发 MindCrew JWT
```

### 4.2 入口参数

`GET /api/cs/sso/entry`

| 参数 | 必填 | 说明 |
|---|---|---|
| `appId` | 是 | 对接应用标识 |
| `userId` | 是 | 外部系统用户 ID |
| `name` | 否 | 外部用户名（用于展示与建号昵称） |
| `ts` | 是 | 秒级时间戳，允许 ±300 秒 |
| `nonce` | 是 | 随机串，Redis 600 秒防重放 |
| `sign` | 是 | 签名，见 4.3 |
| `redirect` | 否 | 站内相对路径，默认 `/cs`；只允许站内路径 |

### 4.3 签名算法

```
sign = hex(HMAC_SHA256(appSecret, appId + "\n" + externalUserId + "\n" + ts + "\n" + nonce))
```

即：四段字符串用换行符 `\n` 拼接（末尾不加换行），用 `appSecret` 做 HMAC-SHA256，输出小写十六进制。

### 4.4 签名 shell 示例

```bash
#!/usr/bin/env bash
set -euo pipefail

HOST="https://mindcrew.example.com"   # 换成实际域名
APP_ID="cs-demo-001"                  # 后台创建应用时得到
APP_SECRET="后台只显示一次的明文密钥"
EXTERNAL_USER_ID="u_10086"
USER_NAME="张三"

TS=$(date +%s)
NONCE=$(openssl rand -hex 8)

# 关键：四段用 \n 拼接，末尾不加换行
SIGN=$(printf '%s\n%s\n%s\n%s' "$APP_ID" "$EXTERNAL_USER_ID" "$TS" "$NONCE" \
  | openssl dgst -sha256 -hmac "$APP_SECRET" | awk '{print $NF}')

# 生成一次性的登录跳转地址并打印 302 的 Location
curl -s -o /dev/null -w '%{redirect_url}\n' -G "$HOST/api/cs/sso/entry" \
  --data-urlencode "appId=$APP_ID" \
  --data-urlencode "userId=$EXTERNAL_USER_ID" \
  --data-urlencode "name=$USER_NAME" \
  --data-urlencode "ts=$TS" \
  --data-urlencode "nonce=$NONCE" \
  --data-urlencode "sign=$SIGN" \
  --data-urlencode "redirect=/cs"
```

正常输出形如 `https://mindcrew.example.com/cs?ticket=xxxxxxxx`；浏览器打开该地址即可免登录进入客服页，或直接把用户 302 过去。

### 4.5 换取登录态

`POST /api/cs/sso/exchange`，请求体 `{"ticket":"..."}`：

```bash
curl -s -X POST "$HOST/api/cs/sso/exchange" \
  -H 'Content-Type: application/json' \
  -d '{"ticket":"<从 302 Location 中取到的 ticket>"}'
```

返回：

```json
{
  "token": "eyJ...",
  "user": { "id": 12, "username": "sso_cs-demo-001_u_10086", "nickname": "张三", "role": "user" },
  "appId": "cs-demo-001"
}
```

- ticket 一次性，换取后立即删除；过期或重复使用都会失败。
- `autoCreateUser=1` 且本地无映射时自动建号：用户名 `sso_{appId}_{externalUserId}`（截断 50 字符，冲突加 `_2`），角色取 `defaultRole`，昵称取 `name`。

### 4.6 两种跳转模式

| 模式 | 配置 | 行为 |
|---|---|---|
| 内部 | `sso_mode=internal` | 302 到站内 `/cs?ticket=...`，前端换取 JWT 后进入我们的客服页 |
| 外部 | `sso_mode=external` | 302 到 `external_cs_url?mindcrewToken={JWT}&externalUserId={id}`，JWT 有效期 24 小时 |

外部模式适合客户已有自己的客服页面、只想复用 MindCrew 的问答能力。

### 4.7 失败处理

| 场景 | 表现 |
|---|---|
| 签名错误 / 时间戳超窗 / nonce 重放 | 302 到 `redirect + "?cs_error=invalid_sign"` |
| `sso_enabled=0` 或应用 `enabled=0` | 按验签失败处理 |
| `redirect` 不是站内相对路径 | 拒绝，按失败处理（防开放重定向） |
| ticket 过期或已消费 | `/exchange` 返回业务错误 |

## 五、钉钉渠道对接

### 5.1 配置

1. 在钉钉开放平台创建机器人，拿到 AppKey / AppSecret / robotCode；
2. 在「客服中心配置 → 钉钉渠道」填入，并设置回调验签密钥 `signSecret`、知识库范围与欢迎语；
3. 把回调地址填到钉钉机器人：

```
POST https://mindcrew.example.com/api/cs/dingtalk/callback?channelId=1&timestamp=xxx&sign=xxx
```

### 5.2 验签

配置了 `signSecret` 时校验：

```
sign = base64(HMAC_SHA256(signSecret, timestamp + "\n" + appSecret))
```

校验失败返回 401。请求体为钉钉 outgoing 机器人 JSON，取 `senderStaffId` / `senderNick` / `text.content` 作为访客标识、昵称与问题。

### 5.3 响应

```json
{
  "msgtype": "markdown",
  "markdown": {
    "title": "MindCrew 智能客服",
    "text": "..."
  }
}
```

回答走同一套 RAG，与网页端共享知识库范围配置。

## 六、常见问题

| 现象 | 排查方向 |
|---|---|
| 公开页 401「访客凭证无效」 | 是否带 `X-CS-Token`；token 是否超过 7 天；是否清过 sessionStorage |
| 消息一直 pending 不返回 | 检查后端日志与 LLM 配置；AI 异常会兜底转人工 |
| 转人工没有触发 | `handoff_keywords` 是否为空；`ai_enabled` 是否为 0（0 时直接人工） |
| 页面配置改了不生效 | 确认保存成功；公开页刷新；检查是否改了另一条配置记录（`cs_config` 只应有 id=1） |
| SSO 一直 `cs_error=invalid_sign` | 签名拼接顺序/换行、时间戳单位（秒）、appSecret 是否用错、`sso_enabled` 与应用 `enabled` 是否为 1 |
| SSO 换 token 失败 | ticket 是否已消费/过期（300 秒）；是否重复调用 exchange |
| 钉钉回调 401 | `timestamp + "\n" + appSecret` 的拼接与 base64 输出是否一致；通道是否启用 |
| 管理接口 403 | 当前账号不是 admin |
| 配置变更没有审计记录 | 检查审计日志 action 是否以 `cs.` 开头；审计是否被关闭 |
