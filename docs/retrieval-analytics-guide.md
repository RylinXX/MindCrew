# 检索分析使用手册（指标口径与排查）

> 适用页面：`/retrieval-analytics`（菜单「运维与监控 → 检索分析」）
> 适用接口：`/api/v2/retrieval/**`（只读，`ADMIN` / `AUDITOR` 角色可用）
> 本文中的口径与实现一一对应，排查时可以直接按「指标 → 数据来源 → 判定条件」对照。

## 一、数据来源

所有指标都来自 `qa_message` 表中 `role = 'assistant'` 的回答记录，不额外建表：

| 字段 | 用途 |
|---|---|
| `retrieval_log` | JSON，记录改写、召回与重排过程（见下表） |
| `reflection_log` | JSON 数组，自检纠错每轮的结论 |
| `feedback` | 1 有用 / -1 无用 / 0 未评 |
| `latency_ms` | 单条回答耗时（毫秒）；为空时回退旧字段 `response_time` |
| `model_name` | 本条回答使用的模型 |
| `input_tokens` / `output_tokens` | Token 用量 |
| `cost_cny` | 本条成本（元） |
| `create_time` | 统计时间归属 |

`retrieval_log` 里参与统计的键：

| 键 | 含义 |
|---|---|
| `originalQuery` | 用户原始问题 |
| `rewrittenQuery` | 改写后的问题 |
| `intentType` | 意图类型（Agent 路径通常为 `llm_driven`） |
| `selectedTools` | LLM 选中的检索工具数组 |
| `retrievalMode` | `search` 或 `selected_document` |
| `selectedKbIds` | 本次检索限定的知识库 ID 数组 |
| `vectorResults` / `bm25Results` / `webResults` | 向量 / BM25 / 联网三路召回条数 |
| `rrfCount` | RRF 融合后的候选数 |
| `rerankTop` | 重排后保留的条数 |
| `memoryKeys` | 命中的长期记忆键 |
| `isFallback` | 命中兜底标记（仅 `RagPipeline` 路径写入） |

> 边界：`isFallback` 不是所有路径都写。Agent 路径没有该字段，统计算法用「`isFallback = true` 或 `rerankTop <= 0`」判定兜底，见下文。
> 边界：非法/空 JSON 直接跳过，不会返回 500；因此 `withLog` 通常小于 `totalAnswers`。

## 二、统计范围

- 时间窗口为「最近 N 个自然日」，含今天，起止为 `今天-N+1 00:00` 到 `明天 00:00`。
- 单次查询最多扫描 `N × 500` 条回答，超出截断，后端日志会打一条 warn。
- `days` 默认 30，前台提供 7 / 30 / 90 三档；接口侧小于 1 按 1 处理，大于 365 按 365 处理。
- 空数据时所有计数为 0、所有比例为 0，不返回 null。

## 三、总览指标（`GET /overview`）

以下 `withLog` = 时间窗口内 `retrieval_log` 能正常解析为 JSON 的回答数；所有比例保留 4 位小数。

| 字段 | 含义 | 计算方式 |
|---|---|---|
| `totalAnswers` | 回答总数 | 窗口内 `role='assistant'` 的记录数 |
| `withLog` | 有检索日志的回答数 | `retrieval_log` 非空且可解析为 JSON 对象 |
| `hitCount` | 有效召回数 | `vectorResults > 0` 或 `bm25Results > 0` 或 `webResults > 0` 或 `rerankTop > 0`，任一成立即计 1 |
| `hitRate` | 命中率 | `hitCount / withLog` |
| `zeroResultCount` | 零结果数 | `vectorResults <= 0` 且 `bm25Results <= 0` 且 `webResults <= 0` |
| `zeroResultRate` | 零结果率 | `zeroResultCount / withLog` |
| `fallbackCount` | 疑似兜底数 | `isFallback = true` 或 `rerankTop <= 0` |
| `fallbackRate` | 兜底率 | `fallbackCount / withLog` |
| `rewriteCount` | 改写触发数 | `rewrittenQuery` 非空且与 `originalQuery` 不同 |
| `rewriteRate` | 改写触发率 | `rewriteCount / withLog` |
| `avgVector` | 平均向量召回条数 | 窗口内 `vectorResults` 之和 / `withLog`，保留 2 位小数 |
| `avgBm25` | 平均 BM25 召回条数 | 同上，取 `bm25Results` |
| `avgRrf` | 平均 RRF 候选数 | 同上，取 `rrfCount` |
| `avgRerank` | 平均重排保留条数 | 同上，取 `rerankTop` |
| `avgLatencyMs` | 平均时延（毫秒） | `latency_ms` 均值（为空回退 `response_time`），四舍五入取整；无有效时延记 0 |
| `positiveFeedback` | 有用反馈数 | `feedback > 0` 的回答数 |
| `negativeFeedback` | 无用反馈数 | `feedback < 0` 的回答数 |
| `satisfactionRate` | 反馈满意率 | `positiveFeedback / (positiveFeedback + negativeFeedback)`，无反馈记 0 |
| `totalCostCny` | 总成本（元） | 窗口内 `cost_cny` 求和，保留 4 位小数 |
| `avgCostCny` | 单回答平均成本（元） | `totalCostCny / totalAnswers`，保留 6 位小数（展示时可只取 2~4 位） |

口径要点：

1. **命中 ≠ 答得好**。`hitCount` 只看有没有召回到东西，`rerankTop = 0` 但向量召回到内容时仍算命中，同时也会算兜底。
2. **兜底与零结果不是互补关系**。三路召回都为空必然兜底；但召回到内容、重排后为 0 也算兜底。
3. `hitRate`、`zeroResultRate`、`fallbackRate`、`rewriteRate` 的分母都是 `withLog`，不是 `totalAnswers`。
4. 时延取单条回答的 `latency_ms`；历史数据没有该字段时用 `response_time` 兜底，两者都没有则不计入平均。

## 四、趋势（`GET /trend`）

返回最近 N 天、按日期升序的数组，缺数据的日期补 0：

| 字段 | 含义 | 计算方式 |
|---|---|---|
| `date` | 日期 | `yyyy-MM-dd` |
| `questions` | 当日回答数 | 当日 `role='assistant'` 记录数 |
| `hitRate` | 当日命中率 | 当日命中数 / 当日有日志数（当日无日志记 0） |
| `zeroResultCount` | 当日零结果数 | 判定同总览 |
| `avgLatencyMs` | 当日平均时延 | 当日有效时延均值，取整；无数据记 0 |
| `fallbackCount` | 当日兜底数 | 判定同总览 |

页面把「问答量（柱/线）」和「命中率（副轴）」画在一起。命中率骤降而问答量平稳，通常是某次知识库或模型配置变更导致。

## 五、工具分布（`GET /tools`）

统计 `retrieval_log.selectedTools` 中每个工具被选中的次数：

| 字段 | 含义 | 计算方式 |
|---|---|---|
| `tool` | 工具名 | `doc_search` / `keyword_search` / `web_search` / `recall_memory` / `store_memory` |
| `count` | 被选中次数 | 每条消息里每个工具计 1 次；`selectedTools` 为空的记录计入 `none` |
| `ratio` | 占比 | `count / 全部工具选择次数`，保留 4 位小数 |

按 `count` 降序。`none` 占比高说明该窗口内大量回答没有走工具调用，可结合 `intentType`、`retrievalMode` 和是否命中缓存排查。

## 六、知识库维度（`GET /kbs`）

统计 `selectedKbIds` 中每个知识库被限定查询的次数：

| 字段 | 含义 | 计算方式 |
|---|---|---|
| `kbId` | 知识库 ID | 来自 `selectedKbIds` |
| `kbName` | 知识库名称 | 关联 `kb_knowledge_base.name`，查不到显示 `#id` |
| `count` | 被使用次数 | 一条消息选了多个 KB 时，每个 KB 各计 1 次 |
| `hitRate` | 该 KB 命中率 | 该 KB 参与且本次命中（判定同总览）/ 该 KB 参与次数 |
| `avgRerank` | 平均重排保留条数 | 该 KB 参与记录的 `rerankTop` 均值，保留 2 位小数 |

按 `count` 降序。注意：这里的命中率是「限定该 KB 的问答整体命中率」，不是该 KB 内部切片命中率。

## 七、低质量查询（`GET /low-quality`）

| 参数 | 默认 | 说明 |
|---|---|---|
| `days` | 30 | 时间窗口 |
| `current` | 1 | 页码，小于 1 按 1 处理 |
| `size` | 20 | 每页条数，限制 1~100 |
| `type` | `zero` | `zero` 零结果 / `fallback` 兜底 / `negative` 点踩；未知值按 `zero` 处理 |

三类判定：

| type | 判定条件 |
|---|---|
| `zero` | 日志可解析，且 `vectorResults`、`bm25Results`、`webResults` 全部 `<= 0` |
| `fallback` | 日志可解析，且 `isFallback = true` 或 `rerankTop <= 0` |
| `negative` | `feedback < 0`（不要求有检索日志） |

列表按 `create_time` 倒序，单条记录字段：

| 字段 | 说明 |
|---|---|
| `messageId` / `conversationId` | 回答消息 ID / 会话 ID |
| `question` | 原始问题，取同会话中时间不晚于该回答的最后一条 user 消息 |
| `rewrittenQuery` | 改写后问题，无日志时为 null |
| `vectorResults` / `bm25Results` / `rrfCount` / `rerankTop` | 对应日志值，无日志记 0 |
| `fallback` | 是否疑似兜底，布尔值 |
| `feedback` | 1 / -1 / 0 |
| `latencyMs` | 该回答时延（毫秒） |
| `modelName` | 模型名 |
| `createTime` | `yyyy-MM-dd HH:mm:ss` |

## 八、单条明细（`GET /detail/{messageId}`）

返回回答的完整上下文：`messageId`、`question`、`answer`、`retrievalLog`、`reflectionLog`、`agentTrace`、`sources`、`feedback`、`latencyMs`。

- 仅 `role='assistant'` 的消息可查，其它角色返回 400；消息不存在返回 404。
- JSON 字段按原结构返回；解析失败时退化为原始字符串，不会 500。
- `feedback` 无值为 0；`latencyMs` 无值也为 0。

## 九、CSV 导出（`GET /low-quality/export`）

- 参数：`days`（默认 30）、`type`（默认 `zero`）。
- 文件名：`low-quality-YYYYMMDD.csv`，`Content-Type: text/csv;charset=UTF-8`，附件下载。
- 内容：UTF-8 BOM 开头，Excel 直接打开不乱码；最多导出 5000 行。
- 列：问题、改写后、向量召回、BM25、RRF、重排、兜底、反馈、时延ms、时间。
- 兜底列为 `true` / `false`；单元格中的换行会被替换为空格，含逗号或引号时按 CSV 规则加引号转义。

## 十、排查建议

| 现象 | 优先排查 |
|---|---|
| 所有 KPI 都是 0 | 时间窗口内是否有 `role='assistant'` 数据；`create_time` 是否在窗口内；数据库时区是否一致 |
| `totalAnswers > 0` 但 `withLog = 0` | 回答是否走了旧版本代码或缓存回放；`retrieval_log` 是否为非 JSON 的空串 |
| 命中率偏高、零结果率为 0 | 是否只有 `RagPipeline` 路径的数据；检查 `vectorResults` 是否被误记为非 0 |
| 兜底率高但零结果低 | 重排模型不可用或 `rag.rerank_top_k` 配置过小；看低质量 tab 的 `rerankTop` 是否为 0 |
| 改写触发率异常低 | 短查询（≤10 字）会跳过改写；检查 `rewrittenQuery` 是否等于原文 |
| 工具分布里 `none` 很多 | LLM 未触发函数调用（降级到规则路由），或回答命中缓存 |
| 知识库名显示 `#id` | `kb_knowledge_base` 中对应记录被物理删除或 `id` 对不上 |
| 某 KB 命中率明显低于其他 | 该 KB 文档是否 ready、切片是否为空、是否被 ACL 过滤 |
| 平均时延偏高 | 结合模型名看是否为慢模型；检查重排与联网检索耗时 |
| 反馈满意率低但命中率正常 | 命中不等于答案对，抽查明细里的 `reflectionLog` 与实际来源 |
| 导出中文乱码 | 用 Excel 直接打开 CSV；不要先另存为 ANSI |
