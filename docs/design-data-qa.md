# 设计契约 · AI 问数（Data Q&A / 指标问答）

> 安全优先：**不执行 LLM 生成的任意 SQL**。LLM 只负责把自然语言映射到「白名单指标 + 时间范围 + 维度」，SQL 全部由代码内固定模板生成（参数化），只读。

## 1. 目标

让管理层用自然语言问运营数据，例如：

- 「最近 7 天每天问了多少问题？」
- 「哪个知识库被用得最多？」
- 「本月各个模型的 token 消耗和成本」
- 「最近 30 天平均响应时间」
- 「用户反馈满意率怎么样？」
- 「提问最多的 10 个问题是什么？」

输出：一句话解读 + 表格 + 图表数据 + 用时。

## 2. 指标目录（唯一允许集合）

| key | 名称 | 默认图表 | 支持参数 | 数据源 |
|---|---|---|---|---|
| `question_count` | 每日提问量 | line | days | qa_message(role=user) 按天 |
| `active_users` | 每日活跃用户 | line | days | qa_conversation 去重 user_id 按天 |
| `token_usage` | 每日 Token 用量 | line | days | qa_message 汇总 input/output |
| `cost_by_model` | 模型成本分布 | bar | days | qa_message group by model_name, sum(cost_cny) |
| `latency_trend` | 平均响应时间趋势 | line | days | qa_message avg(latency_ms/response_time) |
| `feedback_ratio` | 反馈满意度 | pie | days | qa_message group by feedback |
| `kb_ranking` | 知识库使用排行 | bar | days, limit | qa_message.retrieval_log.selectedKbIds 解析计数 |
| `top_queries` | 热门问题 TopN | table | days, limit | qa_message(role=user) group by content |
| `usage_by_day` | 每日成本 | line | days | usage_daily group by stat_date sum(cost_cny) |

参数校验：`days` 1~90（默认 7）；`limit` 1~50（默认 10）。越界钳制，不报错。

## 3. 后端类

| 文件 | 职责 |
|---|---|
| `service/dataqa/MetricCatalog.java` | 指标元数据（key/name/description/chartType/示例问题） |
| `service/dataqa/DataQaService.java` | LLM 意图解析（ChatClient + 严格 JSON）→ 参数钳制 → 执行指标查询 → 生成一句话解读（可复用同一次 LLM 或模板）|
| `controller/DataQaController.java` | `POST /api/v2/data-qa/ask`、`GET /api/v2/data-qa/metrics` |

LLM 调用模式（与 `crew/agents/PlannerAgent.java` 一致）：

```java
ChatClient client = ChatClient.builder(aiConfigHolder.getChatModel())
        .defaultSystem(SYSTEM_PROMPT).build();
String raw = client.prompt().user(question).call().content();
```

解析失败/LLM 异常 → 关键词兜底：
- 含「问题/提问/问答」→ question_count；「用户/活跃」→ active_users；「token」→ token_usage；「成本/费用/花费」→ cost_by_model；「响应/延迟/耗时」→ latency_trend；「满意/反馈/点踩」→ feedback_ratio；「知识库/文库」→ kb_ranking；「热门/最多的问题」→ top_queries；都不命中 → 返回 400「暂不支持该指标」，并附 metrics 列表。

指标查询用 MyBatis-Plus `QueryWrapper.select(...).groupBy(...)` + `selectMaps`（只读，别名小写），或先 `selectList` 内存聚合（kb_ranking / top_queries 必须内存聚合）。禁止字符串拼接用户输入。

## 4. 接口契约

### `GET /api/v2/data-qa/metrics`
```json
[{"key":"question_count","name":"每日提问量","description":"按天统计用户提问条数","chartType":"line","example":"最近7天每天提问量"}]
```

### `POST /api/v2/data-qa/ask`
请求：`{"question":"最近30天每个模型的成本","days":null}`
```json
{
  "question": "最近30天每个模型的成本",
  "metric": "cost_by_model",
  "metricName": "模型成本分布",
  "interpreted": {"days": 30, "limit": 10, "dimension": "model_name"},
  "answer": "最近 30 天共消耗 12.34 元，qwen-plus 占 78%。",
  "columns": [{"key":"modelName","label":"模型"},{"key":"costCny","label":"成本(元)"},{"key":"tokens","label":"Token"}],
  "rows": [{"modelName":"qwen-plus","costCny":9.63,"tokens":120000}],
  "chart": {"type":"bar","xKey":"modelName","yKey":"costCny","seriesName":"成本(元)"},
  "elapsedMs": 820
}
```
- 权限：登录即可（`/api/v2/data-qa/**` 保持 authenticated）
- 只读，不写审计日志
- 空数据返回空 rows，`answer` 说明「暂无数据」
- 前端图表读取 `chart.type/xKey/yKey`，rows 即数据

## 5. 前端契约

路由 `/data-qa`，菜单「工作台 → AI 问数（NEW）」。页面：
- 顶部提问框 + 「问一句」按钮 + 指标示例 chip（来自 `/metrics`）
- 回答区：一句话结论卡 + 表格 + ECharts（line/bar/pie 按 chart.type）
- 右侧/底部：支持指标清单（点击填入示例问题）
- 加载态、错误态、空态完整

## 6. 验收

- [ ] 9 个指标都有数据时能正常返回，无数据时 rows 为空不报错
- [ ] 问「今天天气」返回 400 提示暂不支持 + 指标列表
- [ ] days 传 999 被钳制为 90
- [ ] 前端三种图表都能渲染，切换指标不残留旧图
- [ ] 全链路只读，SQL 均为代码模板
