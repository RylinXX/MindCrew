# MindCrew 项目 · Java 后端八股套餐

> 本套餐基于 MindCrew（原 DocMind）项目实战内容编排。每个问题给出标准答案、项目中的具体实现位置、以及可能的追问方向。覆盖 Spring Boot 3 / Spring AI / MyBatis Plus / Redis / MySQL / 并发 / SSE / JVM 等核心技术栈。

---

## 一、Spring Boot 3 与 Spring Framework 6

### 1.1 Spring Boot 3 与 Spring Boot 2 的主要差异是什么？

**答**：

1. 基线升级：Spring Boot 3 要求 JDK 17+、Spring Framework 6+、Jakarta EE 9+。所有 `javax.*` 命名空间替换为 `jakarta.*`（如 `javax.servlet.http.HttpServletRequest` → `jakarta.servlet.http.HttpServletRequest`）。
2. 配置属性绑定更严格，原本宽松匹配的属性会报错。
3. 内置 GraalVM 原生镜像支持，可通过 `@RegisterReflectionForBinding` 等注解辅助 AOT 编译。
4. 内置 Micrometer Observation API，支持统一的指标 + 追踪 + 日志关联。
5. 移除了部分过时模块（如 `spring-boot-starter-jdbc-jpa` 整合方式调整、`@EnableScheduling` 行为变化）。

**项目应用**：[`pom.xml`](../pom.xml) 中 `spring-boot.version` 为 3.4.x，所有 Servlet / Validation / Persistence 相关 import 都是 `jakarta.*`。例如 [`JwtAuthenticationFilter`](../src/main/java/com/simon/MindCrew/security/JwtAuthenticationFilter.java) 中 `import jakarta.servlet.http.HttpServletRequest`。

**追问**：项目迁移到 Spring Boot 3 时遇到哪些坑？答：MyBatis Plus 老版本依赖 `javax.persistence`，需要升级到 3.5.5+；Spring Security 5 → 6 的配置 API 全面 lambda 化；Java 17 的封闭模块（sealed class）和 record 可以用了。

### 1.2 Spring Bean 的生命周期？

**答**：

1. 实例化（`Constructor`）
2. 属性填充（`Setter`/字段注入）
3. `BeanNameAware` / `BeanFactoryAware` / `ApplicationContextAware` 回调
4. `BeanPostProcessor.postProcessBeforeInitialization`
5. `@PostConstruct`
6. `InitializingBean.afterPropertiesSet`
7. 自定义 `init-method`
8. `BeanPostProcessor.postProcessAfterInitialization`（AOP 代理通常在这里生成）
9. Bean 就绪，供应用使用
10. 容器关闭时：`@PreDestroy` → `DisposableBean.destroy` → 自定义 `destroy-method`

**项目应用**：[`AiConfigInitializer`](../src/main/java/com/simon/MindCrew/config/AiConfigInitializer.java) 实现了 `ApplicationRunner`，在所有 Bean 就绪后从 `sys_ai_config` 表加载 22 条配置到 `AiConfigHolder` 内存，避免每次请求都查库。

**追问**：循环依赖怎么解？答：Spring 通过三级缓存解决单例 setter 注入的循环依赖（`singletonObjects` / `earlySingletonObjects` / `singletonFactories`），但构造器循环依赖无解，必须用 `@Lazy` 或者重构。Spring Boot 2.6+ 默认禁用循环依赖，需要显式开启 `spring.main.allow-circular-references=true`。

### 1.3 Spring Boot 自动配置原理？

**答**：

核心是 `@EnableAutoConfiguration` → `AutoConfigurationImportSelector` → 扫描所有 jar 包下 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件（Spring Boot 3 新位置，旧版在 `META-INF/spring.factories`）。

每个自动配置类带有条件注解（`@ConditionalOnClass` / `@ConditionalOnMissingBean` / `@ConditionalOnProperty`），只在条件满足时生效。例如：

- 类路径有 `RedisTemplate` 且没有用户自定义 → 启用 Redis 自动配置
- 用户配置了 `spring.redis.host` → 启用 Redis 连接

**项目应用**：[`MilvusConfig`](../src/main/java/com/simon/MindCrew/config/MilvusConfig.java) 没有用自动配置（Milvus 没有官方 starter），手写 `@Bean` 注册 `MilvusServiceClient`；而 Redis、MyBatis、DataSource 全部依赖 Spring Boot 自动配置。

**追问**：如何排查某个自动配置为什么没生效？启动加 `--debug` 参数会输出 `AUTO-CONFIGURATION REPORT`，明确告诉你 `Positive matches`（生效）和 `Negative matches`（被条件排除）。

### 1.4 `@SpringBootApplication` 注解的组成？

**答**：

它是三个注解的复合：

- `@SpringBootConfiguration`：标记主配置类，本质是 `@Configuration`
- `@EnableAutoConfiguration`：启用自动配置
- `@ComponentScan`：扫描当前包及子包下的 `@Component` / `@Service` / `@Controller` / `@Repository`

**项目应用**：[`MindCrewApplication`](../src/main/java/com/simon/MindCrew/MindCrewApplication.java) 同时加了：

- `@EnableAsync` 启用异步方法支持（`CrewOrchestrator.runAsync` 用）
- `@MapperScan({"com.simon.MindCrew.mapper", "com.simon.MindCrew.crew.mapper"})` 扫描两个 mapper 包（旧业务和 Multi-Agent 模块分离）

**追问**：为什么 `@MapperScan` 不放在 mapper 包里？因为 `@SpringBootApplication` 默认只扫描主类所在包及子包，而 MyBatis Mapper 接口需要被代理生成实现类，必须显式声明扫描路径。

### 1.5 Spring Boot Starter 的本质是什么？

**答**：

Starter 是一个空的 Maven 模块（没有业务代码），只包含 `pom.xml` 中声明的传递依赖。它的作用是把"一个能力需要的所有依赖"打包，开发者只引一个 Starter 就够。

例如 `spring-boot-starter-web` 实际引入：

- `spring-boot-starter`（核心）
- `spring-boot-starter-json`
- `spring-web` / `spring-webmvc`
- `tomcat-embed-core` 等

**追问**：能不能自己写一个 Starter？答：能。新建模块 `xxx-spring-boot-starter`，依赖 `xxx-spring-boot-autoconfigure`，自动配置模块里写 `@Configuration` 类 + `META-INF/spring/...AutoConfiguration.imports` 文件声明这些配置类。Spring AI 的 `spring-ai-mcp-server-webmvc` 就是这种模式。

---

## 二、Spring AI 与 LLM 集成

### 2.1 Spring AI 的 ChatClient 和 ChatModel 是什么关系？

**答**：

- `ChatModel`：底层抽象，对接具体厂商（OpenAI / DashScope / Ollama / Anthropic）。负责把请求发到 LLM API 并返回 `ChatResponse`。
- `ChatClient`：高级别 fluent API，基于 `ChatModel` 构建。提供 `.prompt().system(...).user(...).call().content()` 的链式调用，支持 default system message、default tools、advisors（拦截器）。

类比：`ChatModel` 像 `RestTemplate`，`ChatClient` 像更高级的 `WebClient`/`RestClient`。

**项目应用**：[`AiConfigHolder.getChatModel()`](../src/main/java/com/simon/MindCrew/config/AiConfigHolder.java) 返回当前活跃的 `ChatModel`（支持运行时热切换 qwen-turbo/qwen-plus/qwen-max）。各 Agent 内部用 `ChatClient.builder(aiConfigHolder.getChatModel())...` 构建专属客户端，比如 [`PlannerAgent`](../src/main/java/com/simon/MindCrew/crew/agents/PlannerAgent.java) 用 default system prompt 注入分解策略。

**追问**：为什么 ChatModel 用 `@Bean` 但 ChatClient 每次新建？因为 ChatClient 是轻量级的链式构建器（每个 Agent 需要不同的 system prompt 和 tools），每次 `.builder()` 几乎没开销；ChatModel 维护底层 HTTP 连接池和配置，应该单例。

### 2.2 Function Calling 在 Spring AI 中怎么实现？

**答**：

两种方式：

1. **`@Tool` 注解**（推荐）：在普通 Spring Bean 的方法上标注 `@Tool(description = "...")`，参数用 `@ToolParam(description = "...")`。Spring AI 自动通过反射生成工具描述（JSON Schema）发给 LLM。
2. **`FunctionCallback` 显式注册**：手写 `FunctionCallback.builder().name(...).description(...).inputSchema(...).build()`。

LLM 决定调用工具后，Spring AI 自动反射调用对应方法，把返回值序列化成 JSON 塞回对话上下文，继续下一轮 LLM 推理直到生成 final answer。

**项目应用**：[`DocSearchTool`](../src/main/java/com/simon/MindCrew/mcp/DocSearchTool.java)、`KeywordSearchTool` 等 5 个工具用 `@Tool` 暴露。Agent 内部通过 `ChatClient.builder(...).defaultTools(toolCallbackProvider)` 注入，LLM 自主决定调用顺序。

**追问**：工具调用失败怎么办？答：项目中工具内部 `try-catch` 兜底，返回错误信息字符串。LLM 看到错误信息后会自己决定是重试、换工具还是告知用户。这一点也是参考资料"工具错误处理必须是工具的一部分，不能靠 Agent 框架兜底"的最佳实践。

### 2.3 Spring AI 的 Advisor 机制？

**答**：

Advisor 是 `ChatClient` 的拦截器链，类似 Spring MVC 的 Interceptor。在请求发出前后做处理，常见用途：

- `MessageChatMemoryAdvisor`：自动从 ChatMemory 加载历史并塞入 prompt
- `QuestionAnswerAdvisor`：自动做 RAG 检索并把结果注入 prompt
- `SimpleLoggerAdvisor`：日志记录
- 自定义 Advisor 实现 `RequestResponseAdvisor` / `CallAroundAdvisor`

**项目应用**：项目没有用官方的 `QuestionAnswerAdvisor`（它过于固定，无法支持七步精排）。而是自己实现了 `RagPipeline` 显式控制每一步。这本身也是面试加分点："为什么不直接用 Spring AI 自带的 RAG？因为 Advisor 黑盒，无法插入 RRF 融合 + Cross-Encoder 重排 + 上下文压缩等环节"。

### 2.4 Embedding 在 Spring AI 中怎么调用？

**答**：

```java
@Autowired EmbeddingModel embeddingModel;

float[] vec = embeddingModel.embed("文本");
// 或批量
EmbeddingResponse resp = embeddingModel.call(
    new EmbeddingRequest(List.of("text1", "text2"), EmbeddingOptionsBuilder.builder().build())
);
```

**项目应用**：[`VectorRetriever`](../src/main/java/com/simon/MindCrew/service/rag/VectorRetriever.java) 接收用户 query，调 `embeddingModel.embed(query)` 得到 1024 维向量，然后查询 Milvus collection。

**追问**：为什么用 1024 维而不是 1536 维（OpenAI 默认）？答：DashScope 的 `text-embedding-v3` 默认 1024 维，可调最高 1536。1024 维在中文场景效果足够，存储和检索都更快。

### 2.5 Spring AI MCP Server 是怎么工作的？

**答**：

MCP（Model Context Protocol）是 Anthropic 提出的工具能力开放标准。Spring AI 的 `spring-ai-mcp-server-webmvc` 自动把所有 `@Tool` 注解的 Bean 通过 HTTP/SSE 端点暴露：

- `/mcp/tools/list`：列出可用工具及 schema
- `/mcp/tools/call`：调用具体工具
- `/mcp/sse`：建立 SSE 长连接接收事件

外部 MCP 客户端（Claude Desktop / Cursor / 自研 Agent）发现并调用这些工具，等同于内部 ChatClient 调用同一个 `@Tool` Bean。

**项目应用**：[`application.yml`](../src/main/resources/application.yml) 中 `spring.ai.mcp.server.enabled: true` 开启 MCP Server。项目对外暴露 `doc_search` / `keyword_search` / `web_search` / `recall_memory` / `store_memory` 5 个标准工具，**内部 Agent 和外部 MCP 客户端共享同一组 `@Tool` Bean 实例**。

**追问**：MCP 跟 OpenAPI 有什么区别？MCP 专为 LLM 工具调用设计，schema 描述对模型友好（强调 `description` 字段帮助模型理解）；OpenAPI 是给开发者看的 REST 规范，对模型解读不友好。

---

## 三、Spring Security 与 JWT

### 3.1 JWT 的结构？

**答**：

三段以 `.` 分隔：`Header.Payload.Signature`

- Header：`{"alg":"HS512","typ":"JWT"}`，Base64URL 编码
- Payload：业务声明（`sub` / `exp` / `iat` 等标准字段 + 自定义字段如 `userId`、`role`）
- Signature：`HMACSHA512(base64(header) + "." + base64(payload), secret)`

服务端用同一个 secret 验签，验证通过则信任 payload。

**项目应用**：[`JwtUtils`](../src/main/java/com/simon/MindCrew/common/utils/JwtUtils.java) 用 `io.jsonwebtoken` 库（jjwt 0.12+），secret 从 `application.yml` 的 `jwt.secret` 注入。Payload 含 `userId` / `username` / `role`，过期时间默认 24 小时。

**追问**：JWT 如何注销？答：JWT 是无状态的，本身无法撤销。常见方案：
- 黑名单：注销时把 token 的 jti 存 Redis，TTL = token 剩余有效期。每次请求查 Redis 是否在黑名单。
- 短期 access_token + 长期 refresh_token：access_token 15 分钟过期，注销时只让 refresh_token 失效。

### 3.2 SSE 接口的 JWT 怎么传？

**答**：

浏览器的 `EventSource` API **不支持自定义 Header**（这是规范限制），无法用 `Authorization: Bearer xxx`。两种解法：

1. **URL query 参数**（项目采用）：`/api/v2/chat/stream?token=xxx`
2. **Cookie**：服务端通过 cookie 拿 token（需要正确配置 SameSite 和 CSRF 防护）

**项目应用**：[`JwtAuthenticationFilter.extractToken()`](../src/main/java/com/simon/MindCrew/security/JwtAuthenticationFilter.java) 双路提取：

```java
// 优先 Authorization Header（普通请求）
String bearer = request.getHeader("Authorization");
if (StringUtils.hasText(bearer) && bearer.startsWith("Bearer ")) {
    return bearer.substring(7);
}
// 兜底 URL 参数（SSE）
return request.getParameter("token");
```

**追问**：URL 传 token 会被记录到 Nginx access log 怎么办？答：可以在 Nginx 层用 `log_format` 把 `$arg_token` 替换成 `[REDACTED]`；或者改用 Cookie 方案。

### 3.3 Spring Security 6 的 SecurityFilterChain 怎么配？

**答**：

```java
@Bean
public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http
        .csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v2/auth/**").permitAll()
            .anyRequest().authenticated())
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .build();
}
```

关键点：

- CSRF 关掉（无状态 token 场景）
- session 设为 `STATELESS`（不创建 HttpSession）
- 在 `UsernamePasswordAuthenticationFilter` 之前插入 JWT Filter

**项目应用**：[`SecurityConfig`](../src/main/java/com/simon/MindCrew/config/SecurityConfig.java)。

**追问**：`OncePerRequestFilter` 跟普通 `Filter` 的区别？答：`OncePerRequestFilter` 保证同一个请求在转发（include/forward）时只执行一次。JWT 验证、日志、事务这些逻辑都应该用它。

### 3.4 BCrypt 密码加密为什么不用 MD5？

**答**：

- MD5 是消息摘要算法，不是密码哈希。计算速度极快（GPU 每秒数十亿次），彩虹表攻击容易。
- BCrypt 内置盐 + 慢哈希（默认 cost=10，相当于哈希 2^10 次），单次验证耗时 100ms 左右。即使数据库泄露，暴力破解成本极高。

BCrypt 哈希格式：`$2a$10$<22字符盐><31字符哈希>`，验证时自动从存储的字符串解出盐和 cost。

**项目应用**：注册和登录都通过 `BCryptPasswordEncoder.matches(rawPwd, hashedPwd)` 验证。

**追问**：cost 越大越好吗？答：不是。cost=10 对应 ~100ms，cost=12 是 ~400ms，cost=14 是 ~1.6s。生产环境一般 10-12，太高会被慢登录拖垮接口。

---

## 四、MyBatis Plus

### 4.1 MyBatis 跟 MyBatis Plus 的关系？

**答**：

- MyBatis：半自动 ORM，提供 SQL 映射框架。需要写 XML 或注解 SQL。
- MyBatis Plus：基于 MyBatis 的增强工具，**只增不改**。提供 `BaseMapper`（自动 CRUD）、`LambdaQueryWrapper`（链式 query）、分页插件、逻辑删除、自动填充等。

底层依然是 MyBatis 的 `SqlSession`，只是动态生成了通用 SQL。

**项目应用**：[`AgentTaskMapper`](../src/main/java/com/simon/MindCrew/crew/mapper/AgentTaskMapper.java) 只是 `interface AgentTaskMapper extends BaseMapper<AgentTask> {}`，零代码自动获得 `selectById` / `insert` / `updateById` / `selectList(wrapper)` 等方法。

### 4.2 LambdaQueryWrapper 怎么用？

**答**：

类型安全的链式查询，避免字段名硬编码：

```java
List<AgentStep> steps = stepMapper.selectList(
    new LambdaQueryWrapper<AgentStep>()
        .eq(AgentStep::getTaskId, taskId)
        .orderByAsc(AgentStep::getStepIndex)
);
```

底层通过 `AgentStep::getTaskId` 方法引用反射出字段名 `task_id`（下划线转驼峰由 `mybatis-plus.configuration.map-underscore-to-camel-case` 控制）。

**项目应用**：[`CrewOrchestrator`](../src/main/java/com/simon/MindCrew/crew/orchestrator/CrewOrchestrator.java) 大量使用 `LambdaQueryWrapper` 查询 agent_task / agent_step。

**追问**：为什么不用 `QueryWrapper`（字符串字段名）？答：编译期不安全。如果你重命名字段，`QueryWrapper.eq("task_id", ...)` 不会报错但运行时失败；`LambdaQueryWrapper.eq(AgentStep::getTaskId, ...)` 重命名时 IDE 会提示所有引用。

### 4.3 逻辑删除怎么实现？

**答**：

在实体类标记字段 `@TableLogic`，配合 yml 配置：

```yaml
mybatis-plus:
  global-config:
    db-config:
      logic-delete-field: deleted
      logic-delete-value: 1
      logic-not-delete-value: 0
```

之后 `deleteById()` 不真正 DELETE，而是 `UPDATE ... SET deleted = 1`。所有 SELECT 自动加 `WHERE deleted = 0`。

**项目应用**：[`AgentTask`](../src/main/java/com/simon/MindCrew/crew/entity/AgentTask.java) 等所有核心实体都有 `@TableLogic private Integer deleted`。

**追问**：逻辑删除会让表越来越大怎么办？答：定期归档：把 `deleted=1` 且时间超过 X 个月的数据迁到 `xxx_archive` 表，主表保持精简。Milvus 这种向量库也要同步删除对应 vector。

### 4.4 分页插件怎么配？

**答**：

注册 `MybatisPlusInterceptor` Bean，添加 `PaginationInnerInterceptor(DbType.MYSQL)`：

```java
@Bean
public MybatisPlusInterceptor interceptor() {
    MybatisPlusInterceptor i = new MybatisPlusInterceptor();
    i.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
    return i;
}
```

然后 `IPage<AgentTask> page = mapper.selectPage(new Page<>(current, size), wrapper);`，自动在 SQL 末尾加 `LIMIT ? OFFSET ?`，并通过额外的 COUNT 查询填充 `total`。

**项目应用**：[`CrewController.listTasks`](../src/main/java/com/simon/MindCrew/controller/CrewController.java) 用分页查询任务历史，封装成 `PageVO` 返回前端。

### 4.5 `@TableField(fill = FieldFill.INSERT)` 自动填充？

**答**：

实体类标记字段，再实现 `MetaObjectHandler`：

```java
@Component
public class MyMetaHandler implements MetaObjectHandler {
    public void insertFill(MetaObject obj) {
        strictInsertFill(obj, "createTime", LocalDateTime.class, LocalDateTime.now());
    }
    public void updateFill(MetaObject obj) {
        strictUpdateFill(obj, "updateTime", LocalDateTime.class, LocalDateTime.now());
    }
}
```

INSERT 时自动设 `createTime`，UPDATE 时自动设 `updateTime`，业务代码不用关心。

**项目应用**：所有实体类的 `createTime` / `updateTime` 字段都是自动填充。

---

## 五、MySQL 与数据库设计

### 5.1 InnoDB 索引底层结构？

**答**：

B+ 树。叶子节点存数据，非叶子节点只存索引列和指向子节点的指针。

- 聚簇索引（Clustered Index）：主键索引，叶子节点直接存整行数据。
- 二级索引（Secondary Index）：叶子节点存"索引列值 + 主键值"，查整行需要回表（拿主键再查聚簇索引）。

**项目应用**：`agent_step` 表设计了复合索引 `idx_task_index(task_id, step_index)`，能加速"按任务查所有步骤并按 stepIndex 排序"的查询。

**追问**：覆盖索引是什么？答：查询的所有字段都在某个二级索引里，不用回表。比如 `SELECT task_id, step_index FROM agent_step WHERE task_id = ?`，如果 `(task_id, step_index)` 是索引，整个查询走二级索引就够了。`EXPLAIN` 输出 `Extra: Using index` 表示用了覆盖索引。

### 5.2 事务的 ACID + 隔离级别？

**答**：

- **A** 原子性：事务里所有操作要么全成功要么全失败（undo log）
- **C** 一致性：事务前后数据库满足业务约束（业务层 + 外键 + 唯一索引等共同保证）
- **I** 隔离性：并发事务互不干扰
- **D** 持久性：提交后数据持久化（redo log）

四个隔离级别：
| 级别 | 脏读 | 不可重复读 | 幻读 |
|:---|:---:|:---:|:---:|
| READ UNCOMMITTED | 可能 | 可能 | 可能 |
| READ COMMITTED | 否 | 可能 | 可能 |
| REPEATABLE READ（MySQL 默认）| 否 | 否 | InnoDB 用 MVCC + Next-Key Lock 解决 |
| SERIALIZABLE | 否 | 否 | 否 |

**项目应用**：MyBatis Plus 不显式管理事务，依赖 Spring 的 `@Transactional`。例如知识库上传的"写 MySQL + 写 Milvus"逻辑用 `@Transactional` 包裹 MySQL 部分，Milvus 失败时手动触发补偿（Milvus 不支持 XA 事务）。

**追问**：MVCC 怎么实现？答：每行有隐藏字段 `trx_id`（修改它的事务 ID）和 `roll_pointer`（指向 undo log 中的旧版本）。SELECT 时根据"当前事务可见性规则"决定看哪个版本，从而实现可重复读 + 高并发。

### 5.3 EXPLAIN 怎么看？

**答**：

关键列：

- `type`：访问类型，从好到差 `system > const > eq_ref > ref > range > index > ALL`
- `key`：实际使用的索引
- `rows`：估算扫描行数
- `Extra`：`Using index`（覆盖）、`Using filesort`（额外排序）、`Using temporary`（临时表）

调优时优先消除 `ALL`（全表扫描）和 `Using filesort` / `Using temporary`。

### 5.4 慢 SQL 怎么排查？

**答**：

1. 开启慢查询日志：`slow_query_log = ON`、`long_query_time = 1`
2. `EXPLAIN` 分析执行计划
3. 检查是否走了索引、`rows` 是否合理、有没有 `Using filesort`
4. 看是否能用覆盖索引避免回表
5. 大表关联看 join 顺序（小表驱动大表）
6. 必要时拆 SQL（如把一个大 JOIN 拆成两次查询 + 应用层组装）

### 5.5 项目用 MySQL 8 的新特性？

**答**：

- **窗口函数**：`ROW_NUMBER() OVER (PARTITION BY user_id ORDER BY create_time DESC)` 高效取每个用户最近 N 条任务
- **CTE**：`WITH t AS (SELECT ...)` 提升复杂查询可读性
- **JSON 函数**：`agent_task.kb_ids` 字段存 JSON 数组，查询时可以用 `JSON_CONTAINS(kb_ids, '5')` 判断
- **ngram 全文索引**：`agent_task.query` 上加 `FULLTEXT INDEX ... WITH PARSER ngram`，支持中文全文搜索（之前要靠 ES）

**项目应用**：[`docmind.sql`](../sql/docmind-init.sql) 中 `kb_chunk.content` 字段就用了 `ngram` 全文索引，作为 BM25 之外的兜底全文检索。

---

## 六、Redis

### 6.1 项目里 Redis 用来干什么？

**答**：

1. **热点问答缓存**：`question_hash → answer`，相同问题在 TTL 内直接返回，避免重复 LLM 调用。
2. **跨会话长期记忆**：`memory:userId:topic → JSON`，TTL 30 天。用户说"我是 Java 开发者"会被 `ExplicitMemoryExtractor` 提取并写入。
3. **JWT 黑名单**：注销时把 token 的 jti 存进去，TTL 等于剩余有效期。
4. **限流**：用 `INCR + EXPIRE` 实现固定窗口限流。
5. **分布式锁**：`SET key value NX EX 10` 保证幂等性。

**项目应用**：[`MemoryTool`](../src/main/java/com/simon/MindCrew/mcp/MemoryTool.java) 的 `recall_memory` / `store_memory` 工具操作 Redis；[`RagCacheService`](../src/main/java/com/simon/MindCrew/service/rag/RagCacheService.java) 实现热点缓存。

### 6.2 Redis 缓存穿透/击穿/雪崩怎么解？

**答**：

| 现象 | 原因 | 解法 |
|:---|:---|:---|
| 穿透 | 查不存在的 key（攻击）| 布隆过滤器 + 缓存空值（短 TTL） |
| 击穿 | 单个热 key 过期，瞬间并发打到 DB | 互斥锁（只允许一个线程查 DB 回填）+ 逻辑过期 |
| 雪崩 | 大量 key 同时过期 | 随机 TTL（基础 30 分钟 + 随机 5 分钟）|

### 6.3 Lettuce 跟 Jedis 区别？

**答**：

- Jedis：同步阻塞，每个连接一个线程。需要连接池。
- Lettuce：基于 Netty，单连接支持多线程并发，原生支持响应式 / 异步。Spring Boot 2.x 起默认。

**项目应用**：[`application.yml`](../src/main/resources/application.yml) 配置 `spring.data.redis.lettuce.pool.max-active=8`。注意：即使是单连接安全的 Lettuce，业务一般也配连接池避免阻塞操作时整个 EventLoop 卡住。

### 6.4 Redis 持久化两种方式？

**答**：

- **RDB**：定期 fork 子进程把内存全量快照写到 dump.rdb。优点恢复快，缺点宕机丢失最近一次快照后的数据。
- **AOF**：每次写命令追加到 appendonly.aof。`appendfsync` 三种：always（每次写 fsync，最安全最慢）、everysec（每秒 fsync，默认，宕机最多丢 1 秒）、no（交给 OS）。

生产推荐 `everysec`，灾难恢复时 RDB + AOF 混合。

**项目应用**：[`docker-compose.dev.yml`](../docker-compose.dev.yml) 中 Redis 配置 `--save 60 1`（60 秒内有 1 次写就 RDB 快照），开发够用。生产建议同时开 AOF。

---

## 七、并发与异步编程

### 7.1 `synchronized` 跟 `ReentrantLock` 区别？

**答**：

| | synchronized | ReentrantLock |
|:---|:---|:---|
| 实现 | JVM 关键字，monitorenter/exit 字节码 | JUC 类，基于 AQS |
| 可中断 | 否 | 否（普通 lock） / 是（lockInterruptibly） |
| 公平 | 非公平 | 可选公平/非公平 |
| 条件变量 | 一个（wait/notify）| 多个（newCondition）|
| 释放 | 自动（异常也释放）| 手动 unlock（必须在 finally）|
| 性能 | JDK 6 偏向锁/轻量级锁优化后差不多 | 微秒级开销 |

绝大多数场景用 `synchronized` 就够；需要超时、可中断、多条件变量时用 `ReentrantLock`。

### 7.2 ThreadLocal 的原理？内存泄漏怎么解？

**答**：

每个 Thread 内部有 `ThreadLocal.ThreadLocalMap`，key 是 `ThreadLocal` 对象（弱引用），value 是用户存的对象（强引用）。

**内存泄漏**：当 ThreadLocal 对象被回收后，key=null 但 value 还在 map 里。如果线程长寿（线程池里的线程），value 永远不释放。

**解法**：用完务必 `threadLocal.remove()`，尤其是在线程池场景。`try-finally` 兜底。

**项目应用**：[`AgentToolContext`](../src/main/java/com/simon/MindCrew/agent/AgentToolContext.java) 用 ThreadLocal 在 LLM Function Calling 阶段传递 kbIds 和 userId 给工具 Bean，每次请求结束都在 `finally` 里 `AgentToolContext.clear()`。

**追问**：为什么用 ThreadLocal 而不是方法参数？答：LLM 决定调用哪些工具，工具方法签名是固定的（由 `@Tool` 暴露给 LLM），无法增加内部参数。ThreadLocal 是"携带请求级上下文穿透到 Spring AI 框架内部"的标准做法。

### 7.3 线程池的核心参数？

**答**：

`ThreadPoolExecutor(corePoolSize, maximumPoolSize, keepAliveTime, unit, workQueue, threadFactory, handler)`

执行流程：

1. 任务来，未达 corePoolSize 时直接创建新线程
2. 达到 corePoolSize 后，放入 workQueue
3. queue 满了，创建新线程到 maximumPoolSize
4. 仍处理不过来，触发 handler（拒绝策略）

四种拒绝策略：AbortPolicy（抛异常，默认）、CallerRunsPolicy（调用线程自己跑）、DiscardPolicy（丢）、DiscardOldestPolicy（丢最老的）。

**项目应用**：[`CrewOrchestrator`](../src/main/java/com/simon/MindCrew/crew/orchestrator/CrewOrchestrator.java) 的 `researchPool` 用 `Executors.newFixedThreadPool(4)` 控制并发 Researcher 数量。

**追问**：为什么不用 `Executors.newCachedThreadPool`？答：CachedThreadPool 没有上限（Integer.MAX_VALUE），LLM 调用慢，瞬间几百个请求会创建几百个线程把内存打爆。生产建议永远 `new ThreadPoolExecutor(...)` 显式配置。

### 7.4 `CompletableFuture` 怎么用？

**答**：

异步任务编排，比 `Future` 强大。常用 API：

- `supplyAsync(supplier, executor)`：异步执行返回结果
- `thenApply(fn)`：链式处理结果
- `thenCompose(fn)`：扁平化（返回 CF 时不嵌套）
- `thenCombine(other, fn)`：两个任务都完成后合并
- `allOf(...)` / `anyOf(...)`：等所有/任一完成
- `exceptionally(fn)`：异常处理

**项目应用**：`CrewOrchestrator.runResearchersParallel()` 给每个 PlanItem 提交一个 `CompletableFuture.supplyAsync(() -> researcherAgent.research(item), researchPool)`，然后用 `f.get(120, TimeUnit.SECONDS)` 等所有 Researcher 完成。

**追问**：CF 内部出现异常会怎样？答：不会主动抛出，需要通过 `exceptionally` 或 `handle` 处理。如果调用 `get()` 才会拿到 `ExecutionException` 包裹原异常。

### 7.5 `@Async` 怎么工作的？

**答**：

Spring AOP 代理 + 线程池。流程：

1. `@SpringBootApplication` 加 `@EnableAsync`
2. 配置 `Executor` Bean（不配置用默认的 SimpleAsyncTaskExecutor，每次创建新线程！）
3. 方法标 `@Async("executor名")`
4. 调用时被 AOP 代理拦截，提交到线程池后立即返回

**陷阱**：

- 同类内部方法调用 `@Async` 无效（绕过了代理）
- 返回值要是 `void` 或 `Future`/`CompletableFuture`，否则同步执行

**项目应用**：[`CrewOrchestrator.runAsync()`](../src/main/java/com/simon/MindCrew/crew/orchestrator/CrewOrchestrator.java) 加了 `@Async`，Controller 调用后立即返回 SseEmitter 给前端，Agent 后台执行并通过 SSE 推送事件。

### 7.6 SecurityContext 在子线程会丢失吗？

**答**：

会。`SecurityContextHolder` 默认用 `ThreadLocal` 存储，子线程拿不到。

**解法**：用 `DelegatingSecurityContextExecutorService` 包装线程池，自动把主线程的 SecurityContext 复制到子线程：

```java
ExecutorService exec = new DelegatingSecurityContextExecutorService(
    Executors.newCachedThreadPool());
```

**项目应用**：[`DocMindChatController`](../src/main/java/com/simon/MindCrew/controller/DocMindChatController.java) 用了这个包装类，确保异步处理 SSE 流时 `userService.getCurrentUserId()` 能拿到用户 ID。

---

## 八、SSE 流式输出

### 8.1 SSE 跟 WebSocket 怎么选？

**答**：

| | SSE | WebSocket |
|:---|:---|:---|
| 协议 | HTTP | 独立协议（升级握手）|
| 方向 | 服务器 → 客户端单向 | 双向 |
| 自动重连 | 浏览器原生 EventSource 自动重连 | 需自己实现 |
| 防火墙友好 | 是（80/443）| 偶尔被中间代理拦截 |
| 数据格式 | 文本 only | 文本 + 二进制 |
| 开销 | 轻量 | 略重 |

LLM 流式回答属于"服务器主动推"，SSE 完全够用，且开发简单。聊天室、协作编辑这种"双向高频"用 WebSocket。

**项目应用**：所有 LLM 流式输出（聊天问答、Multi-Agent 事件流）用 SSE。

### 8.2 Spring 的 SseEmitter 怎么用？

**答**：

```java
@GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public SseEmitter stream() {
    SseEmitter emitter = new SseEmitter(120_000L);  // 超时 ms
    executor.execute(() -> {
        try {
            emitter.send(SseEmitter.event().name("token").data("hello"));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    });
    return emitter;
}
```

关键点：

- Controller 立即返回 SseEmitter，不阻塞 servlet 线程
- 业务线程异步通过 `emitter.send(...)` 推送
- 长任务建议超时 600 秒以上（默认 30s）

**项目应用**：[`CrewController.streamTask()`](../src/main/java/com/simon/MindCrew/controller/CrewController.java) 设了 10 分钟超时，因为 Multi-Agent 调研可能跑几分钟。

### 8.3 SSE 长连接怎么避免 Nginx 截断？

**答**：

Nginx 默认对响应做缓冲，会破坏 SSE 实时性。配置：

```nginx
location /api/v2/chat/stream {
    proxy_pass http://backend;
    proxy_buffering off;          # 关闭响应缓冲
    proxy_cache off;              # 关闭缓存
    proxy_read_timeout 600s;      # 读超时拉长
    proxy_send_timeout 600s;
    add_header X-Accel-Buffering no;  # 兜底，告诉 Nginx 不缓冲
}
```

### 8.4 SSE 事件格式？

**答**：

```
event: token
id: 1
data: {"delta":"你好"}

event: done
data: [DONE]

```

每个事件 `event/id/data` 三类字段，每行 `\n` 分隔，事件之间空行分隔。Spring 的 `SseEmitter.event().name("token").id("1").data(...)` 自动生成符合规范的格式。

---

## 九、JVM 与 Java 17 特性

### 9.1 JVM 内存结构？

**答**：

线程私有：

- **程序计数器**：当前线程执行字节码的行号
- **虚拟机栈**：方法调用栈，每个栈帧含局部变量表 / 操作数栈 / 动态链接 / 返回地址
- **本地方法栈**：native 方法栈

线程共享：

- **堆**：对象实例 + 数组，GC 主要区域。分新生代（Eden + 2 个 Survivor）和老年代
- **方法区**（JDK 8+ 是 Metaspace，存在堆外）：类元数据、运行时常量池、JIT 编译代码

### 9.2 G1 跟 ZGC 怎么选？

**答**：

- **G1**：JDK 9+ 默认。Region 分区，预测式回收，大部分场景 STW < 200ms。
- **ZGC**：JDK 11 引入（11 实验，15 GA），目标 STW < 10ms 且与堆大小无关。代价是吞吐降低 ~5%。
- **Shenandoah**：和 ZGC 类似，OpenJDK 提供。

JDK 17 默认 G1，堆 < 16G 够用。LLM 服务这种"延迟敏感"的场景可以试 ZGC：`-XX:+UseZGC`。

**项目应用**：当前用 JDK 17 默认 G1。如果上线后发现单次 GC 暂停影响 SSE 流式响应，可以切 ZGC。

### 9.3 Java 17 有哪些新特性项目用到了？

**答**：

1. **Text Block**（JDK 15 GA）：三引号字符串字面量，写多行 prompt 极方便。
   ```java
   private static final String SYSTEM = """
       你是任务规划师。
       请输出严格 JSON…
       """;
   ```
   `PlannerAgent`、`WriterAgent`、`CriticAgent` 的 system prompt 全部用 Text Block。

2. **Record**（JDK 16）：不可变 DTO 一行搞定。
   ```java
   public record PlanItem(int index, String title, String query, String section) {}
   ```
   （项目里 `PlanItem` 用了 Lombok `@Data`，本质等价；新项目可以用 record。）

3. **Sealed Class**（JDK 17）：限制继承范围，做有限状态机很合适。
   ```java
   public sealed interface AgentEvent permits TaskStart, PlannerPlan, ResearcherFinding, ...
   ```

4. **Pattern Matching for instanceof / switch**：简化类型判断。

5. **NullPointerException 增强**：明确告诉你哪个变量是 null（`Cannot invoke "X.getY()" because "x" is null`）。

### 9.4 OOM 排查思路？

**答**：

1. JVM 启动参数加 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/path/dump.hprof`
2. OOM 时自动生成 heap dump
3. 用 MAT (Memory Analyzer Tool) 打开，看 Dominator Tree
4. 找最大的对象路径，定位泄漏点

常见原因：

- ThreadLocal 没 remove
- 静态集合无限累加
- 缓存没设上限
- 连接池/线程池配置过大

---

## 十、Milvus / MinIO / Lucene 中间件

### 10.1 Milvus 是什么？跟传统数据库的区别？

**答**：

向量数据库，专为高维向量相似度检索设计。底层基于 ANN（近似最近邻）算法（HNSW / IVF），不做精确匹配。

| | MySQL | Milvus |
|:---|:---|:---|
| 数据类型 | 结构化 | 高维向量 + 元数据 |
| 查询 | SQL 精确条件 | 相似度 Top-K（COSINE / L2 / IP）|
| 索引 | B+ 树 | HNSW / IVF_FLAT / IVF_PQ |
| 一致性 | ACID | 最终一致 |

**项目应用**：[`MilvusConfig`](../src/main/java/com/simon/MindCrew/config/MilvusConfig.java) 配 collection `docmind_knowledge`，维度 1024（匹配 text-embedding-v3），索引 HNSW + COSINE。

### 10.2 Milvus 集合 schema 怎么设计？

**答**：

字段类型：

- `id`（INT64，主键）
- `vector`（FLOAT_VECTOR，dim=1024）
- 元数据字段（INT64 / VARCHAR）用于过滤

查询 = 向量相似度 + 元数据过滤：

```python
collection.search(vectors, "vector", search_params,
    limit=10, expr="kb_id in [1,2,3] and category == 'tech'")
```

**项目应用**：`docmind_knowledge` collection 含 `id, vector, kb_id, chunk_id`。检索时按 `kb_id in (...)` 限定知识库范围。

### 10.3 MinIO 跟 S3 关系？

**答**：

MinIO 是 S3 兼容协议的对象存储，开源自部署。同样的 SDK（AWS S3 SDK）可以同时操作 AWS S3 / 阿里云 OSS / MinIO，只需要换 endpoint。

**项目应用**：[`MinioService`](../src/main/java/com/simon/MindCrew/service/knowledge/MinioService.java) 用 `MinioClient` 上传原始文档到 bucket `docmind`，URL 形式存 MySQL 的 `kb_document.minio_url` 字段。

### 10.4 Lucene BM25 怎么用？

**答**：

BM25 是 TF-IDF 的改进版，全文检索的标准算法。Lucene 默认 Similarity 就是 BM25。

中文需要分词器（默认 StandardAnalyzer 按字符分，不准）。项目用 SmartCN（智能中文分词）：

```java
Analyzer analyzer = new SmartChineseAnalyzer();
Directory dir = FSDirectory.open(Paths.get("index"));
IndexWriter writer = new IndexWriter(dir, new IndexWriterConfig(analyzer));
```

**项目应用**：[`BM25Retriever`](../src/main/java/com/simon/MindCrew/service/rag/BM25Retriever.java) 在本地磁盘维护 Lucene 索引，作为向量检索的补充（BM25 擅长精确术语和编号匹配，向量擅长语义相似）。

### 10.5 为什么不用 Elasticsearch 而用 Lucene 直接嵌入？

**答**：

- ES 是 Lucene 的分布式封装，部署复杂、内存大、API 远程调用有网络开销。
- 项目当前规模（百万级 chunk）单机 Lucene 完全够用，嵌入式调用零延迟。
- 缺点：扩展到分布式需要重构（但那时再上 ES 也不晚，迁移成本可控）。

---

## 十一、设计模式（项目中实际用到的）

### 11.1 策略模式

**场景**：根据问题类型选择不同的检索策略（pure vector / vector + bm25 / + web）。

**项目应用**：[`QueryRouter`](../src/main/java/com/simon/MindCrew/agent/QueryRouter.java) 把策略选择委托给 LLM Function Calling（动态决策），同时保留 `QueryRouter.classifyByRegex()` 作为规则降级路径（双策略可切换）。

### 11.2 模板方法模式

**场景**：所有 Agent（Planner / Researcher / Writer / Critic）的执行框架一致：startStep → 调 LLM → finishStep + emit event。

**项目应用**：`CrewOrchestrator` 把每个 Agent 阶段封装成方法，所有 phase 共享 `startStep / finishStep / failStep / emit` 工具方法，子方法只填充阶段差异。

### 11.3 责任链模式

**场景**：Spring Security 的 FilterChain；Spring AI 的 AdvisorChain。

**项目应用**：[`JwtAuthenticationFilter`](../src/main/java/com/simon/MindCrew/security/JwtAuthenticationFilter.java) 是责任链的一环，通过 `filterChain.doFilter(request, response)` 把请求向后传递。

### 11.4 观察者模式 / 发布订阅

**场景**：SSE 事件流。

**项目应用**：`CrewOrchestrator` 通过 `emitter.send()` 推送事件，前端 EventSource 订阅。整个 Multi-Agent 执行流被建模成事件序列（task.start / planner.plan / researcher.finding / writer.token / critic.review / task.done）。

### 11.5 建造者模式

**场景**：Spring AI 的 ChatClient / CrewEvent / LambdaQueryWrapper 的链式 API。

**项目应用**：[`CrewEvent.of("task.start").role(...).step(...).put("k", "v").progress(0.1)`](../src/main/java/com/simon/MindCrew/crew/dto/CrewEvent.java)。

---

## 十二、工程化

### 12.1 全局异常处理怎么做？

**答**：

`@RestControllerAdvice` + `@ExceptionHandler`。统一封装成 `Result<T>` 返回。

```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public Result<?> handle(BusinessException e) {
        return Result.error(e.getCode(), e.getMessage());
    }
    @ExceptionHandler(Exception.class)
    public Result<?> handleAll(Exception e) {
        log.error("uncaught", e);
        return Result.error(500, "服务器内部错误");
    }
}
```

**项目应用**：[`GlobalExceptionHandler`](../src/main/java/com/simon/MindCrew/common/exception/GlobalExceptionHandler.java) 处理业务异常 + 参数校验异常 + 兜底。SSE 接口需要特殊处理（异常时直接 `emitter.completeWithError`，不能走 JSON 响应）。

### 12.2 配置热加载怎么实现？

**答**：

项目用"数据库存配置 + 内存缓存 + 显式 refresh" 的方案。

- 表 `sys_ai_config` 存所有 RAG/LLM/缓存参数
- `AiConfigHolder` 启动时全量加载到 ConcurrentHashMap
- 管理后台修改配置后调 `/api/v2/admin/ai-config/refresh` 重新加载
- LLM 模型甚至支持热切换（rebuild `ChatModel` Bean）

**项目应用**：[`AiConfigHolder`](../src/main/java/com/simon/MindCrew/config/AiConfigHolder.java) 用 `ConcurrentHashMap<String, String>` 存配置，提供类型化 getter（`getInt("rag.rrf_k_constant")`）。

**追问**：为什么不用 Nacos？答：项目规模不大，自己造比依赖中间件更简单。生产规模上去后切 Nacos 是 1 周工作量。

### 12.3 日志规范？

**答**：

用 SLF4J + Logback。规范：

- 业务关键节点 `INFO`
- 异常详情 `WARN` / `ERROR`
- 调试 `DEBUG`（生产关掉）
- 不要 `e.printStackTrace()`，用 `log.error("xxx", e)`

格式：

```
%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n
```

**项目应用**：所有 Agent 类都用 `@Slf4j`，日志前缀加模块标识便于过滤（如 `[PlannerAgent] start planning, query=...`）。

### 12.4 Lombok 用过哪些注解？

**答**：

- `@Data`：自动 getter/setter/equals/hashCode/toString
- `@RequiredArgsConstructor`：自动 final 字段的构造器（Spring 推荐用构造器注入）
- `@Slf4j`：自动 `private static final Logger log = LoggerFactory.getLogger(...)`
- `@Builder`：建造者模式
- `@NoArgsConstructor` / `@AllArgsConstructor`

**项目应用**：几乎每个 Service / Controller 都是 `@RequiredArgsConstructor + private final ...`（构造器注入 + 不可变 + 单元测试友好）。

### 12.5 Docker 化部署的关键点？

**答**：

1. 多阶段构建：build 阶段用 Maven 镜像编译，run 阶段用 JRE 镜像（体积小）
2. 内存：`-Xmx` 不要等于容器 limit，留 200M 给非堆和容器开销
3. 端口暴露 + 网络配置
4. 数据卷挂载持久化数据（MySQL data / Milvus data）
5. 健康检查：`HEALTHCHECK CMD curl -f http://localhost:8080/actuator/health`

**项目应用**：[`docker-compose.dev.yml`](../docker-compose.dev.yml) 起 Redis / MinIO / etcd / Milvus 四个基础设施。后端通过 IDEA 本地跑（开发期改代码秒级热部署）。生产部署时再补 Dockerfile + 完整 compose。

---

## 附录 A：项目里能挖出来的"亮点 STAR 故事"

每个故事按 Situation / Task / Action / Result 准备：

### A.1 SSE 流式输出导致 SecurityContext 丢失

**S**：前端 SSE 接口跟普通 Controller 处于不同线程，调用 `userService.getCurrentUserId()` 返回 null。
**T**：需要在异步线程里也能拿到当前登录用户。
**A**：用 `DelegatingSecurityContextExecutorService` 包装线程池，自动传递 SecurityContext 到子线程。
**R**：用户上下文跨线程传递问题彻底解决，没有牺牲异步性能。

### A.2 ThreadLocal 跨 Spring AI 框架传递 kbIds

**S**：LLM Function Calling 是 Spring AI 框架内部决定的，工具方法签名固定，没法加 kbIds 参数。
**T**：让工具方法能拿到当前请求的 kbIds 和 userId。
**A**：设计 `AgentToolContext` ThreadLocal，请求开始时 `activate(kbIds, userId)`，工具方法内 `AgentToolContext.get()` 取，请求结束 finally 块清理。
**R**：成功穿透框架，工具能感知请求级上下文，无任何侵入。

### A.3 数据库枚举大小写不一致导致前端筛选失败

**S**：后端枚举 `AgentRole.PLANNER.getCode()` 返回 `"Planner"`，前端按 `s.agentRole === 'PLANNER'` 筛选，永远 false。
**T**：定位为什么图谱视图节点全空。
**A**：在数据加载层做 `agentRole = agentRole.toUpperCase()` 标准化，所有比较用大写。同时建议团队规范"枚举对外用 enum.name() 不用自定义 code"。
**R**：修复后视图正常。同类问题 0 复现。

### A.4 Milvus 向量维度不一致导致数据失效

**S**：项目曾用 1536 维 embedding（OpenAI ada-002），切换 DashScope text-embedding-v3 后维度变 1024。Milvus collection schema 不匹配。
**T**：迁移已有向量数据到新维度。
**A**：写迁移脚本，分批读 MySQL 中的 chunk，调新 embedding 模型重算，写入新 collection。期间通过路由控制新老流量比例。
**R**：零停机切换，旧 collection 保留 1 周后删除。

### A.5 N+1 查询导致知识库列表慢

**S**：知识库列表页要显示每个 KB 的 chunk 数量，循环每个 KB 都查一次 `SELECT COUNT(*) FROM kb_chunk WHERE kb_id = ?`。
**T**：优化列表加载从 800ms 到 100ms 以内。
**A**：改成一次 GROUP BY 查询：`SELECT kb_id, COUNT(*) FROM kb_chunk GROUP BY kb_id`，应用层做 Map 合并。
**R**：响应时间从 800ms 降到 60ms。

---

## 附录 B：简历项目描述模板（项目部分直接抄）

### 500 字版本

**MindCrew · 企业级 Multi-Agent 智能知识库系统**
2026.03 - 2026.06 · 个人作品

技术栈：Spring Boot 3.4 + Spring AI 1.1 + Vue 3 + MyBatis Plus + Milvus + Lucene + Redis + MySQL

项目概述：基于 Agentic RAG 架构的企业知识检索系统。核心特性是**四 Agent 协作研究系统**（Planner / Researcher × N / Writer / Critic），把复杂问题分解 → 并行检索 → 合成结构化报告 → 质量自审。通过 MCP 标准协议对外暴露工具能力。

核心贡献：

1. 设计七步 Agentic RAG 检索精排链路：Query Rewrite → LLM Tool Selection → 多路召回（Vector + BM25）→ RRF 融合 → Cross-Encoder 重排 → 上下文压缩 → 自检纠错。每环节独立组件 + 独立降级策略。
2. 实现 4 Agent 协同状态机（Planner → Researcher × N 并行 → Writer → Critic），全过程 SSE 事件流可视化。Researcher 并行调度用 `CompletableFuture`，子任务失败不影响其他路径。
3. 落地 Time-Travel 调试系统，支持对任意 Agent 步骤的输出做编辑+从该步重跑，每种角色独立的重跑语义（Planner 全链重跑、Researcher 重写报告、Writer 重审、Critic 触发重写循环）。
4. 通过 Spring AI MCP Server 把 5 个工具（doc_search / keyword_search / web_search / recall_memory / store_memory）以标准协议暴露，内外双轨调用复用同一组 `@Tool` Bean。
5. 设计 ThreadLocal `AgentToolContext` 跨 Spring AI 框架传递请求级上下文（kbIds / userId），不侵入工具方法签名。
6. 落地 22 项 RAG/LLM 参数数据库热配置 + 模型运行时热切换（qwen-turbo/plus/max），无需重启。
