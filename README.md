# MindCrew · Agentic RAG 企业知识检索与智能客服中台

> 不只是搜索，而是让企业知识库像专家一样检索、决策、自证。

[![Java](https://img.shields.io/badge/Java-17-orange?logo=openjdk&logoColor=white&style=flat-square)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.5-6DB33F?logo=springboot&logoColor=white&style=flat-square)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-1.1.4-6DB33F?logo=spring&logoColor=white&style=flat-square)](https://spring.io/projects/spring-ai)
[![Vue](https://img.shields.io/badge/Vue-3.5-42B883?logo=vuedotjs&logoColor=white&style=flat-square)](https://vuejs.org/)
[![Milvus](https://img.shields.io/badge/Milvus-2.x-00A1EA?logo=milvus&logoColor=white&style=flat-square)](https://milvus.io/)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white&style=flat-square)](https://docs.docker.com/compose/)
[![License](https://img.shields.io/badge/License-See%20README-lightgrey?style=flat-square)](#-许可)

**MindCrew** 是一套基于 **Agentic RAG（检索增强生成）** 架构的企业级知识检索与智能客服系统。它把分散在 PDF / Word / Markdown 中的企业文档，转化为可直接对话、精准溯源的智能知识资产：检索引擎不是固定流水线，而是由 LLM 实时驱动的 Agent 决策循环；同时通过 **MCP（Model Context Protocol）** 标准协议把知识库能力开放给外部 AI Agent、自动化流程与 IDE 插件。

除知识库问答外，还内置 **智能客服中心**（匿名客服页 / 坐席工作台 / SSO 单点登录 / 钉钉机器人渠道）、**检索分析看板**、**AI 问数** 与 **数字员工交付物** 等运营侧能力，全部复用同一条 Agentic RAG 主链路。

---

## ✨ 核心能力

| 能力 | 说明 |
|:-----|:-----|
| **Agentic RAG 检索决策循环** | LLM 通过 Spring AI `ChatClient` Function Calling 在运行时决定调用哪些工具、是否追加调用；工具调用失败自动降级为 `QueryRouter` 规则路由（正则 + 意图分类），模型异常不中断链路 |
| **混合检索** | Milvus 向量检索（COSINE，1024 维，`text-embedding-v3`）+ Apache Lucene BM25（SmartCN 中文分词）双路召回，经 RRF 倒数秩融合（k=60）统一排序 |
| **Cross-Encoder 精排** | DashScope `gte-rerank` 对候选切片精细打分，取 Top-6；模型不可用时降级为关键词频率评分 |
| **知识库管理 / 文档解析** | PDF / DOCX / Markdown / TXT 上传 → 文本抽取 → 智能切片（段落 + 句子 + 滑动窗口）→ 元数据提取（章节 / 页码 / 分类）→ MySQL + Milvus 双索引；支持处理状态追踪（uploading → processing → ready / error）与重新处理 |
| **智能客服中心** | 匿名客服页 `/cs`（页面外观由 `cs_config` 驱动）、坐席工作台 `/cs-console`（认领 / 回复 / 转接 / 关闭、会话统计）、管理配置；访客 token 为 HMAC-SHA256 签发（7 天）；客服 AI 使用专用机器人账号 `cs_robot` 走同一条 RAG 链路 |
| **SSO 单点登录** | 客户系统按 `HMAC-SHA256(appSecret, appId\nuserId\nts\nnonce)` 签名跳转 `/api/cs/sso/entry`，验签后一次性 ticket（Redis 300s）换 MindCrew JWT；时间戳 ±300s、nonce 防重放 |
| **钉钉机器人渠道** | outgoing 机器人消息回调 `/api/cs/dingtalk/callback`，验签后按发送人建立会话、走 RAG 回答并返回 markdown 消息；管理端提供不真正发送的「测试」接口 |
| **MCP 工具对外暴露** | Spring AI MCP Server（HTTP/SSE）暴露 `doc_search`、`keyword_search`、`web_search`、`recall_memory`、`store_memory` 5 个标准工具；内部 Agent 与外部 MCP 客户端共享同一组实现 |
| **SSE 流式问答** | 字符级流式输出，回答附带精确来源（文档名 / 章节 / 页码 / URL）与推理链（agent_trace、mcp_calls、reflection_log） |
| **检索分析** | 召回质量、改写效果、工具分布、知识库维度、低质量查询、成本与时延统一看板，低质量清单支持 CSV 导出（UTF-8 BOM） |
| **AI 问数** | 自然语言映射到 9 个白名单指标，SQL 由代码内固定模板参数化生成（只读、不执行 LLM 生成的任意 SQL），输出结论 + 表格 + ECharts 图表 |
| **数字员工交付物** | Crew 调研报告与合同可导出 PPTX / DOCX / Markdown，内置采购 / 服务合同模板 |
| **跨会话长期记忆** | 用户偏好自动提取写入 Redis（TTL 30 天），下次对话自动召回；RAG / LLM / 缓存参数支持数据库动态配置与模型热切换 |

### Agentic RAG 七步链路

```
Query Rewrite            LLM 改写口语化问题（≤10 字跳过，失败回退原查询）
   ↓
LLM Tool Selection       Function Calling 动态选择 doc_search / keyword_search / web_search / recall_memory / store_memory
   ↓
RRF Fusion               多路召回结果倒数秩融合（k=60，内容前缀去重，跨路命中标记 HYBRID）
   ↓
Cross-Encoder Rerank     gte-rerank 精排取 Top-6（不可用则关键词频率评分降级）
   ↓
Context Compression      前缀去重 + 单切片截断（800 字）+ 总量控制
   ↓
Prompt Assembly          带来源标注的参考文档 + 用户画像 + 长期记忆 + 最近 6 条对话；Safety Guard 做紧急关键词与置信度检查
   ↓
LLM Streaming             SSE 流式输出 + LLM 自检纠错（事实一致性 / 完整性 / 来源匹配，置信度 < 0.7 最多重试 2 轮）
```

---

## 🏗 系统架构

```
浏览器（Vue 3 SPA，/api 与 /uploads 由 Nginx 反代）
        │  HTTP / SSE
        ▼
┌───────────────────────────────  frontend · nginx:80  ───────────────────────────────┐
│  / 静态资源        /cs 客服页        /cs-console 坐席工作台        /retrieval-analytics │
└───────────────────────────────────────┬─────────────────────────────────────────────┘
                                        │ /api/*
                                        ▼
┌──────────────────────────────  backend · Spring Boot:8080  ─────────────────────────┐
│  Agentic RAG      MindCrewAgent / QueryRouter / RRF / Reranker / Compressor / 自检    │
│  客服中心          Cs*Service + 5 个 Controller（public / sso / console / admin / 钉钉）│
│  运营能力          检索分析 · AI 问数 · 数字员工交付物 · Crew 多 Agent 调研             │
│  工具层            MCP Server（5 tools，HTTP/SSE）· Function Calling（内部直调）       │
└───────┬──────────────────────┬──────────────────────┬──────────────────────┬─────────┘
        ▼                      ▼                      ▼                      ▼
   MySQL 8                Redis 7                 Milvus 2.x              MinIO / OSS
 元数据 / 会话         缓存 / 长期记忆          1024 维向量库            原始文档对象存储
        │
        └──▶ 阿里云百炼 DashScope（qwen-plus 对话 / text-embedding-v3 嵌入 / gte-rerank 重排）
             └──▶ Tavily Web 搜索（可选，联网时效性问题）
```

---

## 🛠 技术栈

| 层级 | 技术选型 |
|:-----|:---------|
| 后端运行时 | Java 17 · Spring Boot 3.3.5 · Spring AI 1.1.4 |
| AI 模型接入 | Spring AI OpenAI Compatible（阿里云百炼 DashScope / 任意 OpenAI 协议模型） |
| 对话模型 | `qwen-plus`（支持热切换 `qwen-turbo` / `qwen-max`） |
| 嵌入模型 | `text-embedding-v3`（1024 维，与 Milvus 维度一致） |
| 重排序 | `gte-rerank`（Cross-Encoder，DashScope 托管） |
| 向量数据库 | Milvus 2.x（`milvus-sdk-java` 2.3.4，COSINE 相似度） |
| 全文检索 | Apache Lucene 8.11.3（SmartCN 中文分词 + BM25） |
| 元数据存储 | MySQL 8 · MyBatis-Plus 3.5.7 |
| 缓存与记忆 | Redis 7（热点缓存 + 跨会话长期记忆，TTL 30 天） |
| 对象存储 | MinIO（S3 兼容；可切换阿里云 OSS） |
| MCP 服务 | Spring AI MCP Server WebMVC（HTTP/SSE 工具暴露） |
| 实时通信 | SSE 流式输出（WebSocket 用于语音识别代理） |
| 安全认证 | Spring Security + JWT（`jjwt` 0.12.5） |
| 文档处理 | Apache PDFBox 3.0.1 · Apache POI 5.2.5 · LibreOffice（老 Office 格式转换）· FFmpeg（音视频抽帧） |
| 前端 | Vue 3.5 · TypeScript 5.9 · Vite 7 · Element Plus 2.13 · ECharts 6 · Pinia |
| 部署 | Docker Compose 一键拉起前后端与 MySQL / Redis / Milvus / MinIO / etcd |

---

## 📁 目录结构

```
MindCrew/
├── src/main/java/com/simon/MindCrew/
│   ├── agent/                # Agentic RAG 核心：MindCrewAgent、AgentToolContext、
│   │                         #   AgentState、QueryRouter、SelfReflection、记忆提取
│   ├── mcp/                  # MCP 工具：DocSearch / KeywordSearch / WebSearch / Memory
│   ├── service/rag/          # RAG 流水线：VectorRetriever、BM25Retriever、RRFFusion、
│   │                         #   CrossEncoderReranker、QueryRewriter、PromptAssembler、SafetyGuard
│   ├── retrieval/            # ContextCompressor 上下文去重压缩
│   ├── crew/                 # 多 Agent 调研引擎（Planner / Researcher / Writer / Critic）
│   ├── service/              # 业务服务：office 交付物、dataqa 问数、retrieval 检索分析、客服中心 Cs*Service
│   ├── config/               # 配置层：AiConfigHolder、McpToolsConfig、SecurityConfig、WebSearchConfig
│   └── controller/           # API 层：问答(SSE)、知识库、MCP 控制台、用户、Crew、客服、检索分析、交付物、问数
├── src/main/resources/
│   ├── application.yml       # 后端配置（密钥全部走环境变量）
│   └── prompts/              # Prompt 模板
├── MindCrew-frontend/        # Vue 3 + TS 前端（问答 / 知识库 / MCP / Crew / 客服 / 运营看板）
├── sql/                      # 数据库 Schema 与增量迁移（执行顺序见 sql/README.md）
├── docker/                   # MySQL 初始化脚本等容器侧文件
├── docs/                     # 产品 / 技术 / 客服 / 部署 / 接口文档
├── docker-compose.yml        # 全栈一键部署编排
├── Dockerfile                # 后端镜像（多阶段构建）
├── .env.docker.example       # 环境变量模板（可安全提交）
├── DEPLOY.md                 # Docker 全栈部署与排错
├── 快速部署.md                # 最短路径部署
└── 项目介绍.md                # 产品与架构完整介绍
```

---

## 🚀 快速开始

### 方式一：Docker Compose 全栈部署（推荐）

前置：Docker Engine 20.10+ 与 `docker compose` v2，内存 ≥ 8 GB，磁盘 ≥ 20 GB。

```bash
# 1. 进入与 docker-compose.yml 同级的项目根目录
cd MindCrew

# 2. 准备环境变量（.env 已被 .gitignore 排除，不会提交）
cp .env.docker.example .env
vim .env    # 只有一个必填：BAILIAN_API_KEY（阿里云百炼 API Key）

# 3. 一键构建并启动（首次约 5~15 分钟）
docker compose up -d --build

# 4. 查看状态与后端日志
docker compose ps
docker compose logs backend | grep -iE 'Started MindCrew|ERROR' | tail -5
```

首次启动会自动完成：构建前后端镜像 → 拉起 MySQL / Redis / MinIO / etcd / Milvus → MySQL 按序导入 `sql/` 全部脚本 → 创建 MinIO 桶 → 启动后端 → 启动 Nginx 前端。

启动成功后浏览器访问 **`http://<服务器IP>/`**（宿主机默认 80 端口；不是 8080）。

常用入口：

| 入口 | 路径 | 说明 |
|:-----|:-----|:-----|
| 知识问答 | `/` | 主工作台：问答、知识库、会话、MCP 控制台 |
| 对外客服页 | `/cs` | 匿名访客提问、转人工、满意度评价 |
| 坐席工作台 | `/cs-console` | 坐席认领 / 回复 / 转接 / 关闭与统计 |
| 客服中心配置 | `/cs-settings` | 页面配置、SSO 应用、钉钉渠道 |
| 检索分析 | `/retrieval-analytics` | 召回质量 / 工具分布 / 成本时延看板 |
| AI 问数 | `/data-qa` | 自然语言问运营数据 |
| 数字员工 | `/deliverables` | 交付物生成与导出 |

> 端口默认：前端 80、后端 8080、MySQL 3306、Redis 6379、Milvus 19530、MinIO 9000（控制台 9001），均可在 `.env` 中调整。

### 方式二：本地开发（前后端分离）

依赖：JDK 17+、Node.js 20.19+ / 22.12+、MySQL 8、Redis 7；向量检索需 Milvus 2.x，文档对象存储需 MinIO（无 Milvus 时可将 `milvus.enabled` 置 `false`，关键词检索仍可用）。

```bash
# 1. 初始化数据库：按顺序执行 sql/ 下脚本（顺序见 sql/README.md 的「全量初始化顺序」）
mysql -uroot -proot         < sql/docmind-init.sql
mysql -uroot -proot docmind < sql/usage-stats-schema.sql
# ... 其余迁移脚本按 sql/README.md 顺序执行

# 2. 后端（默认 8080，密钥从环境变量读取）
export BAILIAN_API_KEY=sk-your-key
export TAVILY_API_KEY=            # 可选：Web 搜索
export JWT_SECRET=$(openssl rand -base64 48)
export CRYPTO_MASTER_KEY=$(openssl rand -base64 48)
mvn spring-boot:run

# 3. 前端（默认 5173，/api 与 /uploads 已代理到 http://localhost:8080）
cd MindCrew-frontend
npm install
npm run dev
```

> Office 文档转换依赖 LibreOffice（`soffice --version` 可验证），音视频解析依赖 FFmpeg；ASR 需要 MinIO 提供公网可访问的预签名 URL。数据库、Redis、Milvus、MinIO 连接信息见 `src/main/resources/application.yml`。

---

## 📚 文档索引

| 文档 | 内容 |
|:-----|:-----|
| [项目介绍](项目介绍.md) | 产品定位、Agentic RAG 决策流程、系统功能与核心亮点（完整版） |
| [DEPLOY.md](DEPLOY.md) | Docker 全栈部署、运维命令、数据卷、切换 OSS、排错速查 |
| [快速部署.md](快速部署.md) | 最短路径部署步骤与常见问题 |
| [docs/阿里云部署指导.md](docs/阿里云部署指导.md) | 阿里云环境部署指导 |
| [docs/本地启动.md](docs/本地启动.md) | 无 Docker 环境的轻量本地启动与降级说明 |
| [docs/kb-product-overview.md](docs/kb-product-overview.md) | 产品概览：定位、目标用户、核心功能、产品边界 |
| [docs/kb-product-user-guide.md](docs/kb-product-user-guide.md) | 知识库产品使用手册 |
| [docs/kb-tech-architecture.md](docs/kb-tech-architecture.md) | 系统总体架构、核心组件、一次问答的典型流程 |
| [docs/kb-tech-agentic-rag-and-mcp.md](docs/kb-tech-agentic-rag-and-mcp.md) | Agentic RAG 与 MCP 技术细节 |
| [docs/kb-tech-ingestion-and-retrieval.md](docs/kb-tech-ingestion-and-retrieval.md) | 文档入库与检索链路 |
| [docs/cs-center-user-guide.md](docs/cs-center-user-guide.md) | 客服中心使用手册（访客 / 坐席 / 管理员 / SSO / 钉钉） |
| [docs/design-cs-center.md](docs/design-cs-center.md) | 客服中心设计契约：数据模型、类清单、接口契约、验收清单 |
| [docs/api-new-endpoints.md](docs/api-new-endpoints.md) | 客服中心 / 检索分析 / 交付物 / AI 问数接口总表与权限白名单 |
| [docs/retrieval-analytics-guide.md](docs/retrieval-analytics-guide.md) | 检索分析看板使用说明 |
| [docs/data-qa-guide.md](docs/data-qa-guide.md) | AI 问数使用说明 |
| [docs/office-deliverables-guide.md](docs/office-deliverables-guide.md) | 数字员工交付物使用说明 |
| [docs/kb-enterprise-deployment-runbook.md](docs/kb-enterprise-deployment-runbook.md) | 企业部署与运维手册 |
| [sql/README.md](sql/README.md) | 数据库脚本清单与全量初始化顺序 |

---

## 🔐 安全与密钥管理

- **真实密钥只放 `.env` 或系统环境变量**，`.env` 已被 `.gitignore` 排除，不会进入版本库；仓库内只保留占位模板 [`.env.docker.example`](.env.docker.example)。
- `src/main/resources/application.yml` 中的敏感配置全部通过环境变量注入：
  - `BAILIAN_API_KEY`（必填）— 阿里云百炼 API Key
  - `TAVILY_API_KEY`（可选）— Tavily Web 搜索
  - `JWT_SECRET` — JWT 签名密钥，默认值仅为本地占位
  - `CRYPTO_MASTER_KEY` — AES 加密主密钥（LLM Provider API Key 等敏感配置落库加密）
  - `MYSQL_ROOT_PASSWORD` / `REDIS_PASSWORD` / `MINIO_ROOT_PASSWORD` 等见 `.env.docker.example`
- **生产环境务必修改**：`JWT_SECRET`、`CRYPTO_MASTER_KEY`、MySQL / Redis / MinIO 密码，建议用 `openssl rand -base64 48` 生成；SSO `appSecret`、钉钉 `appSecret` / `signSecret` 在库中加密存储，接口只返回一次明文、列表只展示打码值。
- 如果曾把真实密钥写入公开位置，请立即在云控制台吊销并轮换。

---

## 📄 许可

本仓库为 MindCrew 的本地部署与二次整理版本，仓库内未附带独立的开源许可证文件。

在商业使用、对外分发或部署到生产环境之前，请先与源码提供方确认授权条款与使用范围；定制开发或技术支持同样请联系源码维护方。
