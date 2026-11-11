# SQL 迁移文件

所有数据库 Schema / 增量迁移 / 种子数据集中在此目录。

## 文件清单

| # | 文件 | 用途 | 依赖 / 备注 |
|---|---|---|---|
| 1 | `docmind-init.sql` | **完整初始库 dump**（建库 + 用户/知识库/文档/Chunk/对话/AI 配置/MCP 全套主体表 + 种子数据） | 最先执行；会建 `docmind` 库 |
| 2 | `usage-stats-schema.sql` | 任务 13：`model_pricing` / `usage_daily` 表，并给 `qa_message` 增加 `model_name` / `input_tokens` / `output_tokens` / `cost_cny` / `latency_ms` | 依赖 1；**检索分析、AI 问数、成本统计必须执行**（缺列会 500） |
| 3 | `retrieval-log-migration.sql` | 给 `qa_message` 增加 `retrieval_log` 列（RAG 检索日志） | 依赖 1；**检索分析必须执行**；幂等（information_schema 判断） |
| 4 | `dept-position-acl-schema.sql` | 任务 7：`sys_department` / `sys_position` / `kb_acl` 表，`sys_user` 加部门职位字段、`kb_knowledge_base` 加 `visibility` | 依赖 1（先于 5） |
| 5 | `kb-acl-department-migration.sql` | 任务 7 补强：`kb_acl` 增加部门级授权 | 依赖 4 |
| 6 | `kb-category-schema.sql` | AI 自动分类字典表 + `kb_knowledge_base` 分类字段扩展 | 依赖 1（须在 4 之后） |
| 7 | `agent-crew-schema.sql` | Multi-Agent Crew 任务追踪表（`agent_task` / `agent_step`） | 依赖 1（先于 8） |
| 8 | `agent-crew-fork-migration.sql` | 为 `agent_task` 增加 fork 关系字段，支持 Time-Travel 回放 | 依赖 7 |
| 9 | `persona-schema.sql` | Soul 人格表 `system_persona` + 预置人格 | 依赖 1 |
| 10 | `llm-provider-schema.sql` | 跨厂商 LLM Provider 表 + 预置 Provider 模板 | 依赖 1 |
| 11 | `api-key-schema.sql` | 任务 11：`api_key` / `api_call_log`（对外 API） | 依赖 1 |
| 12 | `audit-pii-schema.sql` | 任务 12：`audit_log` / `pii_config`（审计 + 脱敏） | 依赖 1；客服中心配置审计依赖此表 |
| 13 | `feedback-golden-schema.sql` | 任务 6：`qa_feedback` / `qa_golden_pair`（校正反哺） | 依赖 1 |
| 14 | `coach-schema.sql` | 任务 9：`coach_session` / `coach_question` / `coach_answer` | 依赖 1（先于 15） |
| 15 | `coach-source-quote-migration.sql` | 教练模式反幻觉：`coach_question.source_quote` | 依赖 14；幂等 |
| 16 | `conversation-flag-migration.sql` | 任务 13.5：`qa_conversation` 敏感标记字段 + `qa_message` 全文索引 | 依赖 1；幂等 |
| 17 | `voice-persona-schema.sql` | 任务 14：`voice_persona` 音色配置 | 依赖 1 |
| 18 | `cs-center-schema.sql` | 客服中心：`cs_config` / `cs_sso_app` / `cs_sso_user` / `cs_session` / `cs_message` / `cs_dingtalk_channel`，带默认页面与钉钉占位配置 | 依赖 1 的 `sys_user` / `qa_conversation`；带 `DROP TABLE IF EXISTS`，可重复执行但会清空客服中心数据 |

> 任务 2（检索分析）与任务 5（AI 问数）**不是**只读兼容旧库：必须有 2、3 两个迁移的列（`retrieval_log`、`model_name`、`input_tokens`、`output_tokens`、`cost_cny`、`latency_ms`）以及 2 的 `usage_daily` 表，否则相关接口会因缺列/缺表直接报错。

## 全量初始化顺序（新机器）

```bash
# 在项目根目录（与 src/ 同级）执行；先备份旧库
mysql -uroot -proot         < sql/docmind-init.sql                # 1  基础库
mysql -uroot -proot docmind < sql/usage-stats-schema.sql          # 2  qa_message 成本/时延列 + usage_daily
mysql -uroot -proot docmind < sql/retrieval-log-migration.sql     # 3  qa_message.retrieval_log
mysql -uroot -proot docmind < sql/dept-position-acl-schema.sql    # 4  组织/职位/ACL
mysql -uroot -proot docmind < sql/kb-acl-department-migration.sql # 5  ACL 部门级
mysql -uroot -proot docmind < sql/kb-category-schema.sql          # 6  分类字典
mysql -uroot -proot docmind < sql/agent-crew-schema.sql           # 7  Crew
mysql -uroot -proot docmind < sql/agent-crew-fork-migration.sql   # 8  Crew Fork
mysql -uroot -proot docmind < sql/persona-schema.sql              # 9  Soul 人格
mysql -uroot -proot docmind < sql/llm-provider-schema.sql         # 10 LLM Provider
mysql -uroot -proot docmind < sql/api-key-schema.sql              # 11 对外 API
mysql -uroot -proot docmind < sql/audit-pii-schema.sql            # 12 审计 + PII
mysql -uroot -proot docmind < sql/feedback-golden-schema.sql      # 13 反馈 / Golden Pair
mysql -uroot -proot docmind < sql/coach-schema.sql                # 14 教练模式
mysql -uroot -proot docmind < sql/coach-source-quote-migration.sql# 15 教练原文引用
mysql -uroot -proot docmind < sql/conversation-flag-migration.sql # 16 会话标记 / 全文索引
mysql -uroot -proot docmind < sql/voice-persona-schema.sql        # 17 语音音色
mysql -uroot -proot docmind < sql/cs-center-schema.sql            # 18 客服中心
```

## 只升级已有库时

按同样顺序只执行缺失的迁移；其中 2、3 是本次「检索分析 / AI 问数」上线前的必跑项：

```bash
mysql -uroot -p docmind < sql/usage-stats-schema.sql
mysql -uroot -p docmind < sql/retrieval-log-migration.sql
```

> 3 与 15/16 自带幂等判断，可重复执行；2、4~14、17、18 里的 `ALTER TABLE` / `DROP TABLE` 不具备幂等保护，重复执行可能报 Duplicate column/table，执行前请确认。
> 增量迁移类的文件以后请按 `NNNN-描述.sql` 命名（如 `0019-add-xxx.sql`），保留时间顺序。
