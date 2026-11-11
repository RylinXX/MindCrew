# 设计契约 · 检索分析（Retrieval Analytics）

> 与 `docs/design-cs-center.md` 同一批新增功能。前端按本文字段并行开发。

## 1. 目标

把已有 `qa_message.retrieval_log / feedback / latency_ms / model_name / cost_cny` 变成可运营的「检索分析」页面：

- 召回质量：命中率、零结果率、兜底率、平均召回数（向量/BM25/RRF/重排）
- 改写效果：改写触发率、改写前后命中对比（按天）
- 工具分布：`selectedTools` 统计（doc_search / keyword_search / web_search / recall_memory / store_memory）
- 知识库维度：每个 KB 的问答量、命中率、平均重排条数
- 低质量查询：零结果、触发兜底、被点踩的查询清单 + 明细抽屉（含完整 retrieval_log）
- 成本/性能：平均时延、Token 成本汇总（复用 `qa_message.cost_cny`）

## 2. 数据来源

`qa_message`（role='assistant'）：
- `retrieval_log` JSON：`originalQuery, rewrittenQuery, intentType, selectedTools[], retrievalMode, selectedKbIds[], vectorResults, bm25Results, webResults, rrfCount, rerankTop, memoryKeys[], isFallback(可选)`
- `reflection_log` JSON：`[{round,passed,confidence,reason,issues,action}]`
- `feedback`：1 / -1 / 0
- `latency_ms`、`response_time`、`model_name`、`input_tokens`、`output_tokens`、`cost_cny`、`create_time`

> 注意：`isFallback` 只在 `RagPipeline` 路径写入；Agent 路径可能没有该字段。判定「兜底」用：`isFallback=true` 或 `rerankTop==0` 时结合答案文本含兜底话术（可选），实现里以 `isFallback OR rerankTop==0` 计「疑似兜底」，字段名 `fallback`。

## 3. 接口契约（全部 `hasAnyRole('ADMIN','AUDITOR')`）

前缀 `/api/v2/retrieval`，全部返回 `Result<T>`。

### 3.1 `GET /overview?days=30`

```json
{
  "days": 30,
  "totalAnswers": 1234,
  "withLog": 1200,
  "hitCount": 1100,
  "hitRate": 0.916,
  "zeroResultCount": 100,
  "zeroResultRate": 0.083,
  "fallbackCount": 80,
  "fallbackRate": 0.066,
  "rewriteCount": 700,
  "rewriteRate": 0.583,
  "avgVector": 8.3,
  "avgBm25": 7.9,
  "avgRrf": 12.1,
  "avgRerank": 5.6,
  "avgLatencyMs": 4200,
  "positiveFeedback": 90,
  "negativeFeedback": 12,
  "satisfactionRate": 0.882,
  "totalCostCny": 12.34,
  "avgCostCny": 0.011
}
```
所有 ratio 为 0~1 的 double（前端乘 100 展示），保留 4 位小数；空数据返回 0 而非 null。

### 3.2 `GET /trend?days=30`

```json
[{"date":"2026-11-01","questions":40,"hitRate":0.9,"zeroResultCount":4,"avgLatencyMs":3800,"fallbackCount":3}]
```
按天补零，日期升序。

### 3.3 `GET /tools?days=30`

```json
[{"tool":"doc_search","count":800,"ratio":0.62}]
```
按 count 降序；`selectedTools` 为空记入 `"none"`。

### 3.4 `GET /kbs?days=30`

```json
[{"kbId":1,"kbName":"部署手册","count":120,"hitRate":0.95,"avgRerank":5.2}]
```
`kbName` 用 `kb_knowledge_base.name` 补全，查不到显示 `#id`；`selectedKbIds` 多值时每个都计数。

### 3.5 `GET /low-quality?days=30&current=1&size=20&type=zero|fallback|negative`

```json
{
  "records": [{
    "messageId": 1, "conversationId": 2, "question": "...", "rewrittenQuery": "...",
    "vectorResults": 0, "bm25Results": 0, "rrfCount": 0, "rerankTop": 0,
    "fallback": true, "feedback": -1, "latencyMs": 5200, "modelName": "qwen-plus",
    "createTime": "2026-11-01 10:00:00"
  }],
  "total": 20, "current": 1, "size": 20
}
```
排序：`create_time DESC`。type 缺省=zero。

### 3.6 `GET /detail/{messageId}`

```json
{"messageId":1,"question":"...","answer":"...","retrievalLog":{...},"reflectionLog":[...],"agentTrace":[...],"sources":[...],"feedback":1,"latencyMs":4200}
```
对 `role!='assistant'` 的消息返回 400。

### 3.7 `GET /low-quality/export?days=30&type=zero`

返回 CSV 文本（`text/csv`，`Content-Disposition: attachment; filename="low-quality-YYYYMMDD.csv"`），列为：问题、改写后、向量召回、BM25、RRF、重排、兜底、反馈、时延ms、时间。

## 4. 实现约束

- 只读接口，**加 `@PreAuthorize("hasAnyRole('ADMIN','AUDITOR')")`**；不写审计日志（读操作）。
- 解析 JSON 用 `com.alibaba.fastjson2.JSON`；异常/空 JSON 跳过，不能 500。
- 单次查询上限：最多扫描 `days*500` 条（默认 30 天=15000 条），超出截断；日志里 warn 一次。`days` 接口侧允许 1~365（前端仍只给 7/30/90 三档，越界钳制）。
- 性能：允许在内存聚合（首版），接口 P95 < 1.5s（15000 行内）。
- 类清单：`service/retrieval/RetrievalAnalyticsService.java`、`controller/RetrievalAnalyticsController.java`（包 `com.simon.MindCrew.*`，Mapper 用现有 `QaMessageMapper`、`KbKnowledgeBaseMapper`）。

## 5. 前端契约

路由 `/retrieval-analytics`（admin），菜单「运维与监控 → 检索分析」。
页面：时间范围切换（7/30/90 天）→ 8 个 KPI 卡 → 趋势折线（问答量+命中率双轴）→ 工具分布饼图 → KB 榜（横向柱）→ 低质量查询表（类型 tab：零结果/兜底/点踩）→ 点击行右侧抽屉展示 `/detail/{messageId}`（来源、检索日志、自检日志）→ 导出 CSV。

API 模块 `src/api/retrieval.ts`，类型完整（`RetrievalOverview` 等）。
图表用 `echarts`（已在依赖里），按需 `import * as echarts from 'echarts'`。

## 6. 验收

- [ ] 空库/无日志时所有接口 200 且 KPI 为 0
- [ ] 有数据时命中率、零结果率与手工抽查一致
- [ ] 明细抽屉能看到完整 retrieval_log
- [ ] 非管理员访问 403
- [ ] CSV 中文不乱码（加 UTF-8 BOM）
