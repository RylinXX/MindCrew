# MindCrew Seed Documents Index

本文档用于说明 `docs/` 目录下新增的示例知识文档，方便后续导入知识库或进行批量向量化测试。

## 文档清单

1. `kb-tech-architecture.md`
   主题：系统总体架构、核心模块、依赖组件、运行链路

2. `kb-tech-ingestion-and-retrieval.md`
   主题：文档入库、文本切片、向量检索、关键词检索、重排与来源输出

3. `kb-tech-agentic-rag-and-mcp.md`
   主题：Agentic RAG 主链路、工具调度、MCP 暴露方式、内外部调用差异

4. `kb-product-overview.md`
   主题：产品定位、目标用户、核心价值、主要功能、适用场景

5. `kb-product-user-guide.md`
   主题：知识库上传、问答操作、会话管理、结果解释、使用建议

6. `kb-product-faq.md`
   主题：常见问题、故障排查、检索优化、权限与安全说明

7. `kb-enterprise-api-auth-and-permissions.md`
   主题：企业 API 鉴权方式、权限模型、角色与资源控制

8. `kb-enterprise-api-integration-spec.md`
   主题：接口规范、请求约定、分页过滤、幂等与重试规则

9. `kb-enterprise-deployment-runbook.md`
   主题：环境准备、部署拓扑、启动顺序、备份恢复与回滚

10. `kb-enterprise-release-process.md`
    主题：发布流程、灰度策略、回滚条件、上线前后检查

11. `kb-enterprise-version-release-notes-sample.md`
    主题：版本说明样例、兼容性声明、升级步骤、已知问题

12. `kb-enterprise-operations-and-alerting.md`
    主题：监控指标、告警策略、故障分级、处置流程

13. `kb-enterprise-knowledge-governance.md`
    主题：知识库命名、分类、所有权、评审、归档与治理

14. `kb-enterprise-product-feature-spec.md`
    主题：企业实际功能说明，覆盖团队空间、权限、审批与导出

15. `kb-acceptance-question-list.md`
    主题：导入后问答验收提问清单，按架构、接口、部署、版本、产品场景分类

## 建议导入方式

- 如果要测试多主题检索效果，建议将上述文档全部导入。
- 如果要测试技术问答效果，优先导入 `kb-tech-*` 文档。
- 如果要测试产品说明、操作手册和 FAQ 检索，优先导入 `kb-product-*` 文档。
- 如果要测试企业内网场景、接口规范、部署和版本说明，优先导入 `kb-enterprise-*` 文档。
- 如果系统支持分类字段，可将文件分类设置为：
  - 技术：`tech`
  - 产品：`product`
  - 企业规范：`enterprise`
  - 验收清单：`qa`

## 推荐验证问题

- MindCrew 的系统架构由哪些核心组件组成？
- 文档上传之后会经过哪些处理步骤？
- Agentic RAG 和 MCP 在 MindCrew 中分别承担什么职责？
- 普通用户如何上传文档并发起问答？
- 如果检索不到结果，系统会怎么处理？
- 如何提高知识库问答的命中率和可追溯性？
- 对外 API 如何做鉴权、幂等和错误处理？
- 生产环境部署时服务启动顺序是什么？
- 一个版本上线前需要完成哪些检查？
- 哪些场景应该回滚到上一版本？
