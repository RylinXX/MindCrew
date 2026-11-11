# AI 问数使用手册（指标问答）

> 页面入口：`/data-qa`（菜单「工作台 → AI 问数」）
> 适用接口：`GET /api/v2/data-qa/metrics`、`POST /api/v2/data-qa/ask`
> 适用角色：登录用户即可调用（`authenticated`），只读，不写审计日志
> 一句话：用自然语言问运营数据，系统返回「一句话结论 + 表格 + 图表数据 + 耗时」。

## 一、能力与边界

AI 问数不是「自然语言转 SQL 执行器」，而是一套白名单指标问答：

1. LLM 只负责把自然语言问题映射成「指标 + 时间范围 + 维度」；
2. 参数会被钳制到合法区间；
3. SQL 由代码内的固定模板生成（参数化），只读；
4. 返回结构化结果，由调用方渲染表格与图表。

因此，**系统不会执行 LLM 生成的任意 SQL**，也不会访问指标目录之外的数据表。这既是安全边界，也决定了问法要落在指标目录内。

## 二、指标目录

以下 9 个指标是唯一允许集合：

| key | 名称 | 默认图表 | 支持参数 | 数据源与口径 |
|---|---|---|---|---|
| `question_count` | 每日提问量 | line | `days` | `qa_message`（`role='user'`）按天计数 |
| `active_users` | 每日活跃用户 | line | `days` | `qa_conversation` 按天去重 `user_id` |
| `token_usage` | 每日 Token 用量 | line | `days` | `qa_message` 按天汇总 `input_tokens` / `output_tokens` |
| `cost_by_model` | 模型成本分布 | bar | `days` | `qa_message` 按 `model_name` 分组，汇总 `cost_cny`（附 Token） |
| `latency_trend` | 平均响应时间趋势 | line | `days` | `qa_message` 按天取 `latency_ms` 均值（为空回退 `response_time`） |
| `feedback_ratio` | 反馈满意度 | pie | `days` | `qa_message` 按 `feedback` 分组（1 / -1 / 0） |
| `kb_ranking` | 知识库使用排行 | bar | `days`、`limit` | 解析 `qa_message.retrieval_log.selectedKbIds` 计数 |
| `top_queries` | 热门问题 TopN | table | `days`、`limit` | `qa_message`（`role='user'`）按 `content` 分组计数 |
| `usage_by_day` | 每日成本 | line | `days` | `usage_daily` 按 `stat_date` 汇总 `cost_cny` |

参数约束：

| 参数 | 范围 | 默认 | 越界处理 |
|---|---|---|---|
| `days` | 1~90 | 7 | 自动钳制，不报错 |
| `limit` | 1~50 | 10 | 自动钳制，不报错 |

## 三、示例问题

| 想问什么 | 推荐问法 | 命中指标 |
|---|---|---|
| 提问量 | 「最近 7 天每天问了多少问题？」 | `question_count` |
| 活跃用户 | 「最近 30 天每天有多少活跃用户？」 | `active_users` |
| Token 消耗 | 「本月每天 token 用量是多少？」 | `token_usage` |
| 模型成本 | 「最近 30 天每个模型的成本」 | `cost_by_model` |
| 响应时间 | 「最近 30 天平均响应时间」 | `latency_trend` |
| 满意度 | 「用户反馈满意率怎么样？」 | `feedback_ratio` |
| 知识库热度 | 「哪个知识库被用得最多？」 | `kb_ranking` |
| 热门问题 | 「提问最多的 10 个问题是什么？」 | `top_queries` |
| 每日成本 | 「最近一周每天花多少钱？」 | `usage_by_day` |

## 四、接口用法

### 4.1 指标目录

`GET /api/v2/data-qa/metrics`

```json
[
  {
    "key": "question_count",
    "name": "每日提问量",
    "description": "按天统计用户提问条数",
    "chartType": "line",
    "example": "最近7天每天提问量"
  }
]
```

`chartType` 取值：`line` / `bar` / `pie` / `table`。

### 4.2 提问

`POST /api/v2/data-qa/ask`

请求体：

```json
{
  "question": "最近30天每个模型的成本",
  "days": null
}
```

- `question` 必填，自然语言问题。
- `days` 可选，不传时由 LLM 从问题中推断；传了则以传入值为准（同样会钳制到 1~90）。

响应示例：

```json
{
  "question": "最近30天每个模型的成本",
  "metric": "cost_by_model",
  "metricName": "模型成本分布",
  "interpreted": { "days": 30, "limit": 10, "dimension": "model_name" },
  "answer": "最近 30 天共消耗 12.34 元，qwen-plus 占 78%。",
  "columns": [
    { "key": "modelName", "label": "模型" },
    { "key": "costCny", "label": "成本(元)" },
    { "key": "tokens", "label": "Token" }
  ],
  "rows": [
    { "modelName": "qwen-plus", "costCny": 9.63, "tokens": 120000 }
  ],
  "chart": { "type": "bar", "xKey": "modelName", "yKey": "costCny", "seriesName": "成本(元)" },
  "elapsedMs": 820
}
```

字段说明：

| 字段 | 说明 |
|---|---|
| `metric` / `metricName` | 命中的指标 key 与中文名 |
| `interpreted` | 实际生效的参数（`days`、`limit`、可能的 `dimension`） |
| `answer` | 一句话解读；无数据时说明「暂无数据」 |
| `columns` | 列定义，`key` 与 `rows` 内字段对应 |
| `rows` | 数据行数组；无数据为 `[]`，不是 null |
| `chart` | 图表类型与坐标轴字段，`table` 时调用方可直接渲染表格 |
| `elapsedMs` | 本次处理耗时（毫秒） |

### 4.3 错误场景

| 场景 | 表现 |
|---|---|
| 问法与指标无关（如「今天天气」） | 返回业务码 400，提示暂不支持，并附指标列表 |
| LLM 解析失败 | 按关键词兜底匹配指标；仍匹配不到则返回业务码 400 |
| 某指标无数据 | HTTP 200，`rows` 为空数组，`answer` 说明暂无数据 |
| `days` / `limit` 越界 | 自动钳制，不报错 |

关键词兜底规则（LLM 不可用或返回非 JSON 时生效）：

| 问题包含 | 命中指标 |
|---|---|
| 问题 / 提问 / 问答 | `question_count` |
| 用户 / 活跃 | `active_users` |
| token | `token_usage` |
| 成本 / 费用 / 花费 | `cost_by_model` |
| 响应 / 延迟 / 耗时 | `latency_trend` |
| 满意 / 反馈 / 点踩 | `feedback_ratio` |
| 知识库 / 文库 | `kb_ranking` |
| 热门 / 最多的问题 | `top_queries` |

## 五、权限与安全说明

- **登录即可用**：`/api/v2/data-qa/**` 只要求已登录（`authenticated`），不额外要求管理员；如果客户环境需要更严格控制，可在网关或角色层面再加一层限制。
- **只读**：全链路没有写操作，也不写审计日志；查询使用 MyBatis-Plus `QueryWrapper` 下推条件，用户输入不参与 SQL 字符串拼接。
- **白名单指标**：只允许上文 9 个指标；指标之外的问法直接拒绝，不会退化成自由查询。
- **参数钳制**：`days`、`limit` 先钳制再查询，避免大范围扫描拖垮数据库。
- **LLM 不碰 SQL**：模型只输出指标与参数（严格 JSON），SQL 由后端模板生成，即使模型被诱导也无法执行任意查询。
- **数据可见性**：当前版本按「登录用户可查全量指标」设计，不区分部门/知识库 ACL。若客户要求行级数据隔离，需要在上层按用户维度扩展指标模板（本文不包含该能力，请勿默认开启）。

## 六、排查建议

| 现象 | 排查方向 |
|---|---|
| 总是返回业务码 400 | 问题是否包含指标关键词；先调 `/metrics` 看指标清单 |
| `rows` 为空 | 时间窗口内是否真的有数据；`role='user'` 消息是否存在 |
| 成本类指标为 0 | `cost_cny` 是否写入；模型定价是否配置 |
| `latency_trend` 偏高/偏低 | 优先检查 `latency_ms` 是否写入，旧数据回退 `response_time` |
| `kb_ranking` 为空 | `retrieval_log.selectedKbIds` 是否有值；日志 JSON 是否可解析 |
| `interpreted.days` 与预期不符 | 请求里显式传 `days`，避免依赖 LLM 推断 |
