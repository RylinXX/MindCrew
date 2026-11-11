create database if not exists docmind;
use docmind;
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for kb_chunk
-- ----------------------------
DROP TABLE IF EXISTS `kb_chunk`;
CREATE TABLE `kb_chunk`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `kb_id` bigint NOT NULL COMMENT '所属知识库ID',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '切片文本内容',
  `chunk_index` int NULL DEFAULT NULL COMMENT '切片顺序索引',
  `metadata` json NULL COMMENT '元数据(页码、章节标题等)',
  `vector_id` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'Milvus中对应的向量ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_kb_id`(`kb_id` ASC) USING BTREE,
  FULLTEXT INDEX `ft_kb_chunk_content_ngram`(`content`) WITH PARSER `ngram`
) ENGINE = InnoDB AUTO_INCREMENT = 25 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '文档切片表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of kb_chunk
-- ----------------------------
INSERT INTO `kb_chunk` VALUES (1, 4, '# DocMind 技术文档：Agentic RAG 与 MCP 能力说明\n\n## 文档目标\n\n本文档解释 DocMind 中 Agentic RAG 和 MCP 的职责划分、内部调用方式、对外暴露方式，以及它们在问答系统中的协作关系。\n\n## 一、什么是 Agentic RAG\n\n在 DocMind 中，Agentic RAG 指系统不再只是“拿到问题后固定检索再固定生成”，而是会根据问题类型动态决定：\n\n- 是否需要改写查询\n- 应该用哪些工具\n- 是否需要知识库检索\n- 是否需要关键词精查\n- 是否需要网页搜索\n- 是否需要读取或写入长期记忆\n\n这种方式让系统更像一个受约束的任务执行器，而不是单纯的模板流水线。\n\n## 二、DocMind 中的主要工具\n\n### 1. `doc_search`\n\n用途：\n\n- 执行语义向量检索\n- 适合概念解释、相似表达、模糊提问\n\n输入示例：', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (2, 4, '- 查询词\n- topK\n- 可选的知识库范围\n\n### 2. `keyword_search`\n\n用途：\n\n- 执行关键词检索\n- 适合查编号、专有名词、配置项、固定短语\n\n### 3. `web_search`\n\n用途：\n\n- 补充外部网页信息\n- 适合实时更新、外部公告、版本变化、公共资料\n\n### 4. `recall_memory`\n\n用途：\n\n- 读取用户长期记忆\n- 适合追问、个性化回答、延续上下文\n\n### 5. `store_memory`\n\n用途：\n\n- 将用户明确表达的长期偏好写入 Redis\n- 例如角色、称呼、表达偏好、关注主题\n\n## 三、问题进入系统后的决策过程\n\n系统会先做问题路由。常见意图包括：\n\n- 普通知识查询\n- 精确检索\n- 实时信息查询\n- 复合问题\n- 追问问题\n\n不同意图会触发不同工具组合。例如：', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (3, 4, '- 一般查询：`doc_search + keyword_search`\n- 实时问题：`doc_search + keyword_search + web_search`\n- 追问问题：`recall_memory + doc_search`\n\n## 四、内部调用与外部调用的区别\n\n### 内部调用\n\nDocMind 应用内部并不会通过 MCP 协议去回调自己，而是：\n\n- 直接注入工具对应的 Spring Bean\n- 在 `DocMindAgent` 中直接调用工具方法\n\n优点是：\n\n- 调用链更短\n- 性能更稳定\n- 更容易调试\n\n### 外部调用\n\n对外部 Agent、脚本或支持 MCP 的客户端，DocMind 会通过 MCP Server 对外暴露工具能力。外部客户端可以通过 HTTP/SSE 发现工具并调用。\n\n这意味着：', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (4, 4, '- 内部是“本地函数调用”\n- 外部是“标准 MCP 工具调用”\n\n两者共用同一批工具实现，但走不同的接入路径。\n\n## 五、为什么要同时保留 Agentic RAG 和 MCP\n\n### Agentic RAG 解决内部问答编排问题\n\n它负责：\n\n- 怎么决定下一步动作\n- 怎么组织检索与生成\n- 怎么把多路来源合成最终答案\n\n### MCP 解决能力复用问题\n\n它负责：\n\n- 如何把工具能力开放给外部\n- 如何让其他 Agent 或自动化流程复用知识库能力\n- 如何实现工具发现、标准化输入输出和调用记录\n\n## 六、典型调用链示例\n\n### 场景一：技术规范问答\n\n问题：\n\n“权限系统里角色继承怎么配置？”\n\n可能链路：\n\n1. 判断为普通知识库查询\n2. 改写查询\n3. 调用 `doc_search`\n4. 调用 `keyword_search`\n5. 融合重排\n6. 输出带来源的答案', 3, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (5, 4, '### 场景二：版本变化查询\n\n问题：\n\n“最新发布里鉴权方式有变化吗？”\n\n可能链路：\n\n1. 判断为实时问题\n2. 调用 `doc_search`\n3. 调用 `keyword_search`\n4. 调用 `web_search`\n5. 统一编号来源\n6. 给出结论并附上网页链接\n\n### 场景三：追问\n\n问题：\n\n“继续说刚才那个权限模型的限制”\n\n可能链路：\n\n1. 判断为追问\n2. 调用 `recall_memory`\n3. 结合对话历史和知识库继续回答\n\n## 七、MCP 接入时需要注意的问题\n\n- `/mcp` 接口是否要求鉴权\n- 工具输入输出是否稳定\n- 工具异常是否会导致整个请求失败\n- 外部搜索是否已配置\n- 工具调用结果是否需要审计\n\n## 八、工程实践建议', 4, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"warning\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (6, 4, '- 内部业务优先直接复用工具 Bean，不要为内部场景绕一层 MCP\n- 外部集成统一走 MCP，减少私有协议\n- 工具命名保持稳定，避免外部客户端适配成本过高\n- 所有工具返回结构化结果，避免返回不可解析文本\n\n## 总结\n\n在 DocMind 中，Agentic RAG 是“如何编排问题求解过程”，MCP 是“如何开放工具能力给外部调用”。两者不是互相替代关系，而是内部编排与外部集成的两层能力。', 5, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:52:57');
INSERT INTO `kb_chunk` VALUES (7, 5, '# DocMind 产品说明：用户操作手册\n\n## 文档目标\n\n本文档面向普通用户和管理员，说明如何使用 DocMind 完成知识库上传、问答、会话查看和结果解释。\n\n## 一、上传知识库文档\n\n### 操作步骤\n\n1. 进入知识库管理页面\n2. 点击上传文档\n3. 选择文件并填写名称、分类和描述\n4. 等待系统完成处理\n\n### 上传后会发生什么\n\n系统会依次执行：\n\n- 保存原始文件\n- 解析文本\n- 切片\n- 向量化\n- 写入检索索引\n\n只有当状态变为 `ready` 时，该文档才可用于问答。\n\n### 建议上传的文档类型\n\n- 产品说明\n- 接口文档\n- 部署手册\n- 操作流程\n- FAQ\n- 版本记录\n- 规范与制度文件\n\n## 二、发起问答\n\n### 基本方式\n\n在聊天页面直接输入问题，例如：', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:53:15');
INSERT INTO `kb_chunk` VALUES (8, 5, '- “部署前需要准备哪些配置？”\n- “权限模型有哪些核心对象？”\n- “这个功能的使用限制是什么？”\n\n### 限定知识库范围\n\n如果页面支持知识库选择器，建议在提问前选择明确的知识库范围。这样可以：\n\n- 提高命中率\n- 降低噪音结果\n- 让答案更聚焦\n\n## 三、理解问答结果\n\n### 1. 推理过程\n\n部分回答会显示系统执行步骤，例如：\n\n- 意图识别\n- 查询改写\n- 检索\n- 重排\n- 反思审查\n\n这些信息可以帮助用户判断系统为什么给出当前答案。\n\n### 2. 参考来源\n\n当回答下方显示来源面板时，通常包括：\n\n- 来源名称\n- 章节或页码\n- 摘要片段\n- 相关性分数\n\n如果来源是网页结果，还可能包含链接。\n\n### 3. 兜底回答\n\n若系统未检索到足够相关的内容，可能会返回兜底说明。此时应该理解为：\n\n- 当前知识库资料不足\n- 回答参考性较强\n- 更适合补充文档或重新提问', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:53:15');
INSERT INTO `kb_chunk` VALUES (9, 5, '## 四、会话管理\n\n### 新建对话\n\n适合在以下情况使用：\n\n- 问题主题变化较大\n- 不希望旧上下文影响新答案\n\n### 切换历史会话\n\n系统会保存历史记录，方便用户回看：\n\n- 问过什么\n- 系统怎么回答\n- 用过哪些来源\n\n### 删除会话\n\n如果某次对话只用于临时测试，可以删除，避免列表混乱。\n\n## 五、用户反馈\n\n对每条回答，用户可进行简单反馈，例如：\n\n- 有用\n- 无用\n\n反馈的意义在于：\n\n- 帮助管理员识别低质量回答\n- 为后续知识库优化提供依据\n\n## 六、提高问答质量的建议\n\n### 提问尽量具体\n\n比起问“这个怎么做”，更推荐问：\n\n- “在测试环境中如何配置 OAuth 登录？”\n- “导出接口的分页上限是多少？”\n\n### 包含对象和范围\n\n推荐在问题中带上：\n\n- 模块名\n- 场景名\n- 时间范围\n- 版本范围\n\n### 优先上传结构清晰的文档', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:53:15');
INSERT INTO `kb_chunk` VALUES (10, 5, '如果文档本身没有标题层级、章节混乱或内容重复，问答效果会明显下降。\n\n## 七、常见误区\n\n### 误区一：系统知道所有外部知识\n\n不是。系统优先依赖已导入的知识库；若网页检索未启用，则不会主动访问外部网络。\n\n### 误区二：系统回答越长越好\n\n不是。对知识库问答来说，最重要的是：\n\n- 回答相关\n- 来源明确\n- 不编造\n\n### 误区三：没有结果说明系统不可用\n\n很多时候只是：\n\n- 文档还没处理完成\n- 问题范围太大\n- 关键词过于模糊\n- 知识库本身缺资料\n\n## 总结\n\n把 DocMind 用好，关键不是“问得越多越好”，而是“文档沉淀得好、范围选得准、问题问得清”。它更像是团队知识的检索入口，而不是替代文档本身。', 3, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:53:15');
INSERT INTO `kb_chunk` VALUES (11, 6, '# 企业文档：知识库治理规范\n\n## 文档目标\n\n本文档定义知识库治理的基本规范，帮助团队建立统一的命名、分类、评审、归档和责任机制，避免知识库逐渐演变成“文件堆积区”。\n\n## 一、为什么知识库需要治理\n\n知识库系统的效果高度依赖内容质量。如果文档混乱、重复、过期、无责任人，再强的检索能力也很难持续给出高质量答案。\n\n## 二、知识库命名建议\n\n推荐命名结构：\n\n- 主题 + 版本/日期 + 类型\n\n示例：\n\n- `权限系统-接口规范-v1.3`\n- `部署运行手册-生产环境-2026Q1`\n- `产品FAQ-支付模块-2026-03`\n\n不建议的命名：\n\n- `新文档`\n- `最终版`\n- `最新版2`\n- `资料整理`\n\n## 三、分类建议\n\n推荐从业务用途出发分类，而不是只按文件格式分类。\n\n常见分类：\n\n- 技术\n- 产品\n- 运维\n- 规范\n- 法务\n- 客服\n- 通用', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:53:32');
INSERT INTO `kb_chunk` VALUES (12, 6, '## 四、责任人机制\n\n每个知识库建议至少有以下角色之一：\n\n- 业务 owner\n- 文档维护人\n- 审核人\n\n责任不明确时，最容易出现的问题是：\n\n- 文档长期不更新\n- 旧资料无人清理\n- 用户不知道该信哪个版本\n\n## 五、评审机制\n\n建议重要知识文档在入库前至少通过一次内容检查，重点关注：\n\n- 是否为最终有效版本\n- 是否含冲突信息\n- 是否缺少标题层次\n- 是否适合被问答系统检索\n\n## 六、文档生命周期建议\n\n可参考以下状态：\n\n- 草稿\n- 待审核\n- 已发布\n- 已过期\n- 已归档\n\n对已过期内容，建议不要直接删除，可先归档并降低优先级。\n\n## 七、版本管理建议\n\n对频繁变化的资料，建议明确版本管理方式：\n\n- 版本号\n- 生效日期\n- 废弃日期\n- 变更摘要\n\n这类元信息对问答系统尤其重要，因为很多用户会问：\n\n- “最新版规则是什么？”\n- “旧版和新版有什么差异？”', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:53:32');
INSERT INTO `kb_chunk` VALUES (13, 6, '## 八、内容质量建议\n\n更适合入库的文档通常具备：\n\n- 清晰标题\n- 明确章节\n- 少量重复\n- 完整上下文\n- 术语统一\n\n不适合直接入库的资料包括：\n\n- 大量截图拼接文件\n- 纯会议记录\n- 未整理的聊天记录\n- 多版本内容混杂的草稿\n\n## 九、定期治理建议\n\n建议按月或按季度执行：\n\n- 热门问题分析\n- 低命中文档排查\n- 过期文档清理\n- 重复文档合并\n- 缺失知识补录\n\n## 十、总结\n\n知识库治理本质上是“把文档管理成可信知识资产”。对企业来说，系统只是载体，真正决定问答效果的，是知识是否规范、持续、可追责、可演进。', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:53:32');
INSERT INTO `kb_chunk` VALUES (14, 7, '# 企业文档：监控、告警与故障处置\n\n## 文档目标\n\n本文档用于说明知识库问答系统上线后应该重点关注哪些指标、如何设置告警，以及出现故障后如何做初步分级和处置。\n\n## 一、为什么需要监控和告警\n\n知识库问答系统的稳定性不仅取决于应用本身，还依赖多个外部组件：\n\n- 数据库\n- 缓存\n- 对象存储\n- 向量数据库\n- 大模型服务\n- 外部搜索服务\n\n任何一层异常，都可能表现为：\n\n- 文档处理失败\n- 问答超时\n- 结果为空\n- 来源缺失\n- 页面可用但答案质量显著下降\n\n## 二、建议重点监控的指标\n\n### 应用层\n\n- 接口成功率\n- SSE 建连成功率\n- 平均响应时间\n- P95/P99 响应时间\n\n### 检索层\n\n- 向量检索耗时\n- 关键词检索耗时\n- 重排耗时\n- 空结果比例\n\n### 模型层', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:55:23');
INSERT INTO `kb_chunk` VALUES (15, 7, '- Chat Model 调用成功率\n- Embedding 调用成功率\n- 外部搜索调用成功率\n\n### 数据层\n\n- MySQL 连接池使用率\n- Redis 连接与内存使用\n- Milvus 查询耗时\n- MinIO 上传失败率\n\n## 三、建议告警策略\n\n### P1 告警\n\n需要立即处理的场景：\n\n- 登录不可用\n- 问答主链不可用\n- 文档上传全部失败\n- 大量 5xx\n\n### P2 告警\n\n需要尽快处理但可短时观察的场景：\n\n- 问答耗时显著升高\n- 外部搜索大量失败\n- 向量检索耗时异常\n- 某些知识库处理任务连续失败\n\n### P3 告警\n\n用于趋势跟踪：\n\n- 来源缺失率上升\n- 兜底回答比例升高\n- 文档导入量异常下降\n\n## 四、常见故障分级建议\n\n### Sev-1\n\n用户核心功能不可用，影响广泛。\n\n示例：', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:55:23');
INSERT INTO `kb_chunk` VALUES (16, 7, '- 所有问答请求失败\n- 登录全部失败\n- 首页不可访问\n\n### Sev-2\n\n部分核心能力不可用，但可降级运行。\n\n示例：\n\n- 网页搜索不可用\n- 部分知识库无法检索\n- MCP 外部调用失败但内部问答正常\n\n### Sev-3\n\n局部问题或非核心异常。\n\n示例：\n\n- 导出功能异常\n- 某类文档处理失败\n- 某些统计页面数据不完整\n\n## 五、初步处置流程\n\n1. 确认告警是否真实\n2. 判断影响范围\n3. 检查最近发布和配置变更\n4. 检查外部依赖状态\n5. 决定降级还是回滚\n6. 记录处置过程\n\n## 六、典型故障排查路径\n\n### 问答变慢\n\n优先检查：\n\n- 模型调用耗时\n- 向量检索耗时\n- 是否出现大量重试\n- 是否有依赖组件 CPU/内存异常\n\n### 回答频繁无来源\n\n优先检查：', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:55:23');
INSERT INTO `kb_chunk` VALUES (17, 7, '- 知识库是否为空\n- 文档是否导入完成\n- 来源组装逻辑是否异常\n- Prompt 是否被错误裁剪\n\n### 文档导入失败\n\n优先检查：\n\n- 文件格式是否支持\n- 对象存储是否可写\n- 文本抽取是否报错\n- 向量数据库是否可写\n\n## 七、值班建议\n\n- 发布当日安排研发和运维同时在线\n- 核心时段开启重点指标盯盘\n- 对连续失败任务设置自动聚合告警，避免告警风暴\n\n## 总结\n\n监控和告警的目标不是“把所有异常都报警”，而是“在真正影响用户和业务之前尽早识别风险”。对 DocMind 这类多依赖系统，跨层指标和统一排障入口尤为重要。', 3, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"warning\"}', NULL, '2026-04-02 17:55:23');
INSERT INTO `kb_chunk` VALUES (18, 8, '# 企业文档：版本发布流程与上线检查\n\n## 文档目标\n\n本文档用于定义企业系统版本发布的推荐流程，涵盖需求冻结、测试验收、灰度上线、观察期和回滚条件。\n\n## 一、发布目标\n\n一个成熟的发布流程应同时满足：\n\n- 上线可控\n- 风险可回退\n- 责任明确\n- 信息同步\n\n## 二、角色分工\n\n### 产品负责人\n\n- 确认需求范围\n- 明确是否允许变更延期\n- 确认发布公告内容\n\n### 研发负责人\n\n- 确认代码合并范围\n- 确认部署包版本\n- 提供回滚方案\n\n### 测试负责人\n\n- 确认回归结果\n- 给出是否可发布结论\n\n### 运维或平台负责人\n\n- 执行部署\n- 观察上线指标\n- 触发回滚操作\n\n## 三、标准发布流程\n\n### 1. 冻结发布范围\n\n在发布前应明确：\n\n- 哪些功能进入本次发布\n- 哪些缺陷必须修复\n- 哪些需求延后\n\n### 2. 通过测试验收\n\n至少包括：', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:55:30');
INSERT INTO `kb_chunk` VALUES (19, 8, '- 核心功能回归\n- 关键接口验证\n- 问答主链验证\n- 来源展示验证\n- 关键配置验证\n\n### 3. 准备发布包和变更单\n\n发布前应准备：\n\n- 构建版本号\n- 变更说明\n- 配置变更项\n- 数据脚本\n- 回滚步骤\n\n### 4. 执行上线\n\n推荐顺序：\n\n1. 备份关键数据\n2. 执行配置变更\n3. 发布应用\n4. 做健康检查\n5. 做冒烟验证\n\n### 5. 观察期\n\n发布后应重点观察：\n\n- 错误率\n- 响应时间\n- 核心页面与接口\n- 问答成功率\n- 工具调用成功率\n\n## 四、灰度发布建议\n\n适用于：\n\n- 变更范围大\n- 风险未知\n- 涉及核心链路\n\n常见方式：\n\n- 指定用户灰度\n- 指定流量比例灰度\n- 指定租户灰度\n\n灰度阶段重点确认：\n\n- 新功能是否符合预期\n- 老功能是否出现回归\n- 指标是否稳定\n\n## 五、回滚触发条件\n\n建议提前定义清晰的回滚条件，例如：', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:55:30');
INSERT INTO `kb_chunk` VALUES (20, 8, '- 核心接口连续异常\n- 登录或鉴权失败\n- 问答主链不可用\n- 严重数据污染风险\n- 用户投诉集中爆发\n\n## 六、上线检查清单\n\n### 上线前\n\n- 版本号正确\n- 配置文件正确\n- 数据库脚本已审核\n- 回滚方案已确认\n- 责任人在线\n\n### 上线后\n\n- 首页可访问\n- 登录正常\n- 知识库查询正常\n- SSE 问答正常\n- MCP 工具可见\n\n## 七、发布说明建议内容\n\n每次发布说明至少包含：\n\n- 版本号\n- 发布时间\n- 变更摘要\n- 风险提示\n- 升级说明\n- 回滚说明\n- 已知限制\n\n## 八、总结\n\n一个好发布流程的关键不是“步骤很多”，而是“每一步都能降低不确定性”。对知识库问答系统而言，发布时尤其要关注检索链路、来源展示、配置正确性和工具可用性。', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:55:30');
INSERT INTO `kb_chunk` VALUES (21, 9, '# DocMind 产品说明：常见问题 FAQ\n\n## 1. 为什么上传文档后不能马上问答？\n\n因为系统需要先完成：\n\n- 文本抽取\n- 切片\n- 向量化\n- 检索索引构建\n\n只有知识库状态变成 `ready` 之后，文档才真正可检索。\n\n## 2. 为什么我明明上传了文档，却检索不到内容？\n\n可能原因包括：\n\n- 文档尚未处理完成\n- 文档内容提取失败\n- 问题过于模糊\n- 关键术语与文档表达差异过大\n- 没有选中正确的知识库范围\n\n## 3. 为什么答案里有“当前知识库结果不足”的提示？\n\n这表示系统判断：\n\n- 没有检索到足够相关的内容\n- 或者检索结果置信度偏低\n\n这是为了避免无依据回答，属于保护机制，不是故障。\n\n## 4. 为什么同一个问题有时回答不完全一致？\n\n常见原因有：\n\n- 检索候选结果排序发生变化\n- 模型生成存在自然波动\n- 会话上下文不同\n- 是否命中长期记忆不同', 0, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:56:07');
INSERT INTO `kb_chunk` VALUES (22, 9, '如果希望更稳定，可以：\n\n- 指定知识库范围\n- 提问更精确\n- 新开会话避免历史干扰\n\n## 5. 网页搜索和知识库搜索有什么区别？\n\n知识库搜索：\n\n- 优先使用系统内已导入文档\n- 来源更可控\n- 更适合内部规范和沉淀资料\n\n网页搜索：\n\n- 适合补充外部最新信息\n- 依赖外部服务配置\n- 不一定总是启用\n\n## 6. 为什么有些回答没有来源？\n\n通常意味着：\n\n- 没有检索到可信来源\n- 当前回答是兜底说明\n- 知识库内容不足以支撑引用\n\n在正式使用中，应优先信任带来源编号和明确出处的回答。\n\n## 7. 怎样提高检索命中率？\n\n建议从以下方面优化：\n\n- 文档按主题拆分\n- 文件命名规范化\n- 保留章节标题\n- 提问时带关键对象和范围\n- 用更明确的术语替代模糊表达\n\n## 8. 支持哪些文档类型？\n\n常见支持类型包括：\n\n- PDF\n- DOCX\n- Markdown\n- TXT', 1, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"general\"}', NULL, '2026-04-02 17:56:07');
INSERT INTO `kb_chunk` VALUES (23, 9, '不同格式的解析质量可能存在差异，其中结构清晰的 Markdown 和 Word 文档通常更适合入库。\n\n## 9. 长期记忆会记录什么？\n\n系统只应记录用户明确表达、适合长期保留的信息，例如：\n\n- 角色\n- 称呼\n- 表达偏好\n- 关注主题\n\n不应把每一轮普通问题都写入长期记忆。\n\n## 10. MCP 能做什么？\n\nMCP 主要用于把 DocMind 的工具能力开放给外部客户端，包括：\n\n- 文档语义检索\n- 关键词检索\n- 网页检索\n- 长期记忆读取与写入\n\n这使得 DocMind 不只是一个页面产品，也可以作为外部 Agent 的知识能力底座。\n\n## 11. 系统适合哪些团队？\n\n比较适合：\n\n- 产品团队\n- 研发团队\n- 测试团队\n- 运营团队\n- 知识管理团队\n- 内部支持团队\n\n尤其适合文档多、资料散、重复答疑频繁的场景。\n\n## 12. 不适合哪些场景？', 2, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"example\"}', NULL, '2026-04-02 17:56:07');
INSERT INTO `kb_chunk` VALUES (24, 9, '不适合直接拿来替代：\n\n- 强事务系统\n- 审批流系统\n- 复杂协同编辑平台\n- 严格法律或财务自动决策系统\n\nDocMind 更适合做“知识检索和辅助理解”，而不是“自动决策代理”。\n\n## 13. 如何判断知识库内容是否值得补充？\n\n如果你频繁遇到以下情况，就应该补文档：\n\n- 同类问题反复被问\n- 系统经常给出兜底回答\n- 用户需要依赖口头说明才能继续工作\n- 不同成员对同一规则理解不一致\n\n## 14. 管理员最应该关注哪些指标？\n\n- 文档处理成功率\n- 问答响应时间\n- 来源覆盖率\n- 低质量回答反馈数\n- 热门问题分布\n- 高频知识空白点\n\n## 总结\n\nDocMind 的效果高度依赖知识库质量。系统本身可以增强检索和组织答案，但不能替代知识的持续沉淀。把常见问题、关键流程和核心规范及时文档化，才是发挥系统价值的前提。', 3, '{\"chapter\": \"\", \"pageNumber\": 0, \"contentType\": \"procedure\"}', NULL, '2026-04-02 17:56:07');

-- ----------------------------
-- Table structure for kb_knowledge_base
-- ----------------------------
DROP TABLE IF EXISTS `kb_knowledge_base`;
CREATE TABLE `kb_knowledge_base`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '知识库/文档名称',
  `category` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '分类(技术/法律/医疗/通用等)',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '描述',
  `file_url` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '原始文件存储路径(MinIO)',
  `file_type` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '文件类型: pdf/docx/md/txt',
  `file_size` bigint NULL DEFAULT NULL COMMENT '文件大小(字节)',
  `chunk_count` int NOT NULL DEFAULT 0 COMMENT '切片数量',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'uploading' COMMENT '状态: uploading/processing/ready/error',
  `error_msg` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '错误信息',
  `user_id` bigint NOT NULL COMMENT '创建者用户ID',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_category`(`category` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '知识库文档表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of kb_knowledge_base
-- ----------------------------
INSERT INTO `kb_knowledge_base` VALUES (1, 'kb-product-overview.md', 'tech', NULL, 'knowledge/aca7ee9b-50bf-4839-b7ed-97bd371f72d6.md', 'md', 3168, 4, 'ready', NULL, 1, '2026-03-31 21:06:26', '2026-03-31 21:06:26', 0);
INSERT INTO `kb_knowledge_base` VALUES (2, 'kb-acceptance-question-list.md', 'tech', NULL, 'knowledge/61d3ed11-3715-4667-89e7-1c60b70fe4f6.md', 'md', 4345, 6, 'ready', NULL, 1, '2026-03-31 21:07:00', '2026-03-31 21:07:00', 0);
INSERT INTO `kb_knowledge_base` VALUES (3, 'DocMind-智能知识库检索系统-PRD.docx', 'product', NULL, 'knowledge/d98c5ec3-0da1-4a8d-a334-7f51914f9e2a.docx', 'docx', 45293, 26, 'ready', NULL, 1, '2026-03-31 21:07:15', '2026-03-31 21:07:15', 0);
INSERT INTO `kb_knowledge_base` VALUES (4, 'kb-tech-agentic-rag-and-mcp.md', 'tech', NULL, 'knowledge/67d3eeba-d3be-4e7d-ae0d-c3c2c4f8524f.md', 'md', 4402, 6, 'ready', NULL, 1, '2026-04-02 17:52:57', '2026-04-02 17:52:57', 0);
INSERT INTO `kb_knowledge_base` VALUES (5, 'kb-product-user-guide.md', 'product', NULL, 'knowledge/c1dc2338-8865-4b12-a099-fee42859e970.md', 'md', 3559, 4, 'ready', NULL, 1, '2026-04-02 17:53:15', '2026-04-02 17:53:15', 0);
INSERT INTO `kb_knowledge_base` VALUES (6, 'kb-enterprise-knowledge-governance.md', 'training', NULL, 'knowledge/46e23701-9ed4-44d6-be81-2b4b1232b372.md', 'md', 2603, 3, 'ready', NULL, 1, '2026-04-02 17:53:32', '2026-04-02 17:53:32', 0);
INSERT INTO `kb_knowledge_base` VALUES (7, 'kb-enterprise-operations-and-alerting.md', 'legal', NULL, 'knowledge/70f543b1-d45a-4fe0-a892-7743392478c6.md', 'md', 3121, 4, 'ready', NULL, 1, '2026-04-02 17:55:23', '2026-04-02 17:55:23', 0);
INSERT INTO `kb_knowledge_base` VALUES (8, 'kb-enterprise-release-process.md', 'finance', NULL, 'knowledge/ef5b9721-e3dc-40f6-addf-1aad37d70a11.md', 'md', 2651, 3, 'ready', NULL, 1, '2026-04-02 17:55:30', '2026-04-02 17:55:30', 0);
INSERT INTO `kb_knowledge_base` VALUES (9, 'kb-product-faq.md', 'product', NULL, 'knowledge/a63ea3b7-304f-4a52-beb9-27a33682406a.md', 'md', 3743, 4, 'ready', NULL, 1, '2026-04-02 17:56:07', '2026-04-02 17:56:07', 0);

-- ----------------------------
-- Table structure for mcp_tool_registry
-- ----------------------------
DROP TABLE IF EXISTS `mcp_tool_registry`;
CREATE TABLE `mcp_tool_registry`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `name` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '工具名称',
  `description` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '工具描述',
  `mode` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'embedded' COMMENT '模式: embedded/remote',
  `call_count` bigint NOT NULL DEFAULT 0 COMMENT '调用次数',
  `avg_latency_ms` int NOT NULL DEFAULT 0 COMMENT '平均延迟(ms)',
  `status` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'active' COMMENT '状态: active/disabled',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_name`(`name` ASC) USING BTREE,
  INDEX `idx_status`(`status` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 10 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = 'MCP工具注册表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of mcp_tool_registry
-- ----------------------------
INSERT INTO `mcp_tool_registry` VALUES (3, 'web_search', '联网搜索工具，获取实时互联网信息', 'remote', 0, 0, 'active', '2026-03-30 10:36:10', '2026-03-30 10:36:10');
INSERT INTO `mcp_tool_registry` VALUES (6, 'doc_search', '语义向量文档检索工具', 'embedded', 13, 421, 'active', '2026-03-30 15:16:55', '2026-04-02 17:43:19');
INSERT INTO `mcp_tool_registry` VALUES (7, 'keyword_search', '关键词BM25文档检索：根据关键词精确匹配从知识库中检索文档切片', 'embedded', 0, 0, 'active', '2026-04-02 16:20:04', '2026-04-02 16:20:04');
INSERT INTO `mcp_tool_registry` VALUES (8, 'recall_memory', '召回用户长期记忆：从Redis读取用户偏好等跨会话记忆', 'embedded', 0, 0, 'active', '2026-04-02 16:20:04', '2026-04-02 16:20:04');
INSERT INTO `mcp_tool_registry` VALUES (9, 'store_memory', '写入用户长期记忆：将用户明确表达的偏好持久化到Redis', 'embedded', 0, 0, 'active', '2026-04-02 16:20:04', '2026-04-02 16:20:04');

-- ----------------------------
-- Table structure for qa_conversation
-- ----------------------------
DROP TABLE IF EXISTS `qa_conversation`;
CREATE TABLE `qa_conversation`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `user_id` bigint NOT NULL COMMENT '关联用户ID',
  `title` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '会话标题(首次提问自动生成)',
  `kb_ids` json NULL COMMENT '关联的知识库ID列表',
  `message_count` int NOT NULL DEFAULT 0 COMMENT '消息条数',
  `last_active` datetime NULL DEFAULT NULL COMMENT '最后活跃时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE,
  INDEX `idx_last_active`(`last_active` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 15 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '对话会话表' ROW_FORMAT = DYNAMIC;


-- ----------------------------
-- Table structure for qa_message
-- ----------------------------
DROP TABLE IF EXISTS `qa_message`;
CREATE TABLE `qa_message`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `conversation_id` bigint NOT NULL COMMENT '关联会话ID',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '角色: user/assistant',
  `content` longtext CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '消息内容(支持Markdown)',
  `sources` json NULL COMMENT '文档来源(文档名、页码、片段)',
  `agent_trace` json NULL COMMENT 'ReAct推理链(思考→行动→观察)',
  `mcp_calls` json NULL COMMENT 'Tool调用记录',
  `reflection_log` json NULL COMMENT '自纠错审查日志',
  `feedback` tinyint NOT NULL DEFAULT 0 COMMENT '反馈: 1有用 -1无用 0未评',
  `tokens_used` int NULL DEFAULT NULL COMMENT 'Token消耗',
  `response_time` int NULL DEFAULT NULL COMMENT '响应时间(毫秒)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_conversation_id`(`conversation_id` ASC) USING BTREE,
  INDEX `idx_create_time`(`create_time` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 29 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '对话消息表' ROW_FORMAT = DYNAMIC;


-- ----------------------------
-- Table structure for sys_ai_config
-- ----------------------------
DROP TABLE IF EXISTS `sys_ai_config`;
CREATE TABLE `sys_ai_config`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `config_key` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '配置键(全局唯一)',
  `config_value` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '当前配置值',
  `value_type` varchar(16) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL DEFAULT 'string' COMMENT '值类型: string/integer/float',
  `group_name` varchar(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '分组: rag/llm/cache/safety',
  `label` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '前端展示名称',
  `description` varchar(256) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '配置说明',
  `default_value` varchar(512) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '出厂默认值',
  `min_value` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '最小值约束',
  `max_value` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NULL DEFAULT NULL COMMENT '最大值约束',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint(1) NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_config_key`(`config_key` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 23 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci COMMENT = 'AI动态配置表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of sys_ai_config
-- ----------------------------
INSERT INTO `sys_ai_config` VALUES (1, 'rag.vector_top_k', '10', 'integer', 'rag', '向量召回 Top-K', '向量检索返回的最大文档数', '10', '1', '50', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (2, 'rag.bm25_top_k', '10', 'integer', 'rag', 'BM25 召回 Top-K', 'BM25 检索返回的最大文档数', '10', '1', '50', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (3, 'rag.rerank_top_n', '5', 'integer', 'rag', '重排序 Top-N', '重排序后保留的文档数', '5', '1', '20', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (4, 'rag.chunk_size', '512', 'integer', 'rag', '切片大小', '文档切片的最大字符数', '512', '128', '2048', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (5, 'rag.chunk_overlap', '64', 'integer', 'rag', '切片重叠', '相邻切片的重叠字符数', '64', '0', '512', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (6, 'llm.model', 'qwen-plus', 'string', 'llm', '模型名称', '当前使用的 LLM 模型名称', 'qwen-plus', NULL, NULL, '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (7, 'llm.temperature', '0.7', 'float', 'llm', 'Temperature', 'LLM 生成多样性参数（兼容旧配置）', '0.7', '0', '2', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (8, 'llm.chat_temperature', '0.7', 'float', 'llm', '对话模型 Temperature', '普通对话模型的温度参数', '0.7', '0', '2', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (9, 'llm.streaming_temperature', '0.7', 'float', 'llm', '流式模型 Temperature', 'SSE 流式对话模型的温度参数', '0.7', '0', '2', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (10, 'llm.timeout_seconds', '60', 'integer', 'llm', '请求超时(秒)', 'LLM 请求超时时间（秒）', '60', '10', '300', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (11, 'llm.max_tokens', '2048', 'integer', 'llm', '最大输出Token', 'LLM 单次最大输出 Token 数', '2048', '256', '8192', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (12, 'cache.enable', 'true', 'string', 'cache', '启用缓存', '是否启用 RAG 结果缓存', 'true', NULL, NULL, '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (13, 'cache.ttl_seconds', '3600', 'integer', 'cache', '缓存TTL(秒)', 'RAG 缓存有效期（秒）', '3600', '60', '86400', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (14, 'cache.ttl_hours', '1', 'integer', 'cache', '缓存TTL(小时)', 'RAG 缓存有效期（小时）', '1', '1', '720', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (15, 'cache.freq_threshold', '2', 'integer', 'cache', '缓存频次阈值', '同一问题达到该频次后才检查缓存', '2', '1', '100', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (16, 'safety.enable_guard', 'true', 'string', 'safety', '启用安全过滤', '是否开启内容安全审查', 'true', NULL, NULL, '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (17, 'safety.max_retries', '2', 'integer', 'safety', '最大自纠错次数', 'ReAct 自纠错最大重试轮数', '2', '0', '5', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (18, 'safety.confidence_threshold', '0.6', 'float', 'safety', '置信度阈值', '低于此分数触发兜底回答', '0.6', '0', '1', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (19, 'safety.fallback_msg', '抱歉，我暂时无法回答该问题，请联系管理员。', 'string', 'safety', '兜底话术', '无法回答时的默认提示语', '抱歉，我暂时无法回答该问题，请联系管理员。', NULL, NULL, '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (20, 'rag.rrf_top_n', '20', 'integer', 'rag', 'RRF融合 Top-N', 'RRF 融合后保留的候选文档数', '20', '5', '100', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (21, 'rag.rerank_top_k', '6', 'integer', 'rag', '重排序 Top-K', '重排序后最终保留的文档数', '5', '1', '20', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);
INSERT INTO `sys_ai_config` VALUES (22, 'rag.rrf_k_constant', '60', 'integer', 'rag', 'RRF K常数', 'RRF 算法平滑常数，标准值为60', '60', '1', '200', '2026-03-30 10:36:10', '2026-03-30 10:36:10', 0);

-- ----------------------------
-- Table structure for sys_user
-- ----------------------------
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  `username` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '用户名（唯一）',
  `password` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码(BCrypt加密)',
  `nickname` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '昵称',
  `avatar` varchar(500) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '头像URL',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'user' COMMENT '角色: admin/user',
  `preference` json NULL COMMENT '用户偏好(领域、语言风格等)',
  `status` tinyint NOT NULL DEFAULT 1 COMMENT '状态: 0禁用 1正常',
  `last_login` datetime NULL DEFAULT NULL COMMENT '最后登录时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除: 0正常 1删除',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE INDEX `uk_username`(`username` ASC) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 4 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '系统用户表' ROW_FORMAT = DYNAMIC;

-- ----------------------------
-- Records of sys_user
-- ----------------------------
INSERT INTO `sys_user` VALUES (1, 'admin', '$2a$10$yTs1cG5twvTjCp1uFzO20uFuC77vdAYNkqspfcMNr8owiTuBk77/u', '管理员', NULL, 'admin', NULL, 1, '2026-04-02 16:11:32', '2026-03-30 10:36:10', '2026-03-30 10:37:51', 0);
INSERT INTO `sys_user` VALUES (2, 'simon', '$2a$10$yTs1cG5twvTjCp1uFzO20uFuC77vdAYNkqspfcMNr8owiTuBk77/u', 'sss', NULL, 'user', NULL, 1, '2026-03-30 10:37:01', '2026-03-30 10:36:58', '2026-03-30 10:36:58', 0);
INSERT INTO `sys_user` VALUES (3, 'codex0331201014', '$2a$10$SJLJSE5uNRIT2BoWVnTwI.x0I7X/NCkwDrWUd0BotIi/2UTb8VNiO', 'Codex Smoke', NULL, 'user', NULL, 1, '2026-03-31 20:10:24', '2026-03-31 20:10:15', '2026-03-31 20:10:15', 0);

SET FOREIGN_KEY_CHECKS = 1;
