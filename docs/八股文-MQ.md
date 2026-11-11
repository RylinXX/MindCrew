# 八股文 · 消息队列（MQ）

> 覆盖通用概念、RabbitMQ、Kafka、RocketMQ 四大主题。

---

## 一、通用概念

### 1.1 消息队列的使用场景有哪些？（解耦、削峰、异步）

**答**：

**1. 异步**

把耗时操作放到 MQ 异步处理。例如下单后给用户发短信、发邮件、加积分——同步执行要 2 秒，发到 MQ 后接口 50ms 返回，剩下的让消费者慢慢处理。

**2. 解耦**

上游系统不需要知道下游有谁。订单系统发"下单成功"消息，仓储、积分、推荐等系统各自订阅消费。下游新增订阅方时，订单系统代码不动。

**3. 削峰填谷**

突发流量场景。秒杀时 10 万 QPS 涌入，下游处理能力只有 1000/s。先把请求堆到 MQ，下游按自己节奏消费，避免被打挂。MQ 充当蓄水池。

**4. 数据分发 / 广播**

一份数据多方消费。日志、binlog 变更等场景，写到 Kafka 后多个下游（数仓、ES、监控）各自消费。

**5. 顺序处理**

业务要求严格顺序（如订单状态变更）时，MQ 提供顺序消费能力。

**6. 最终一致性 / 分布式事务**

本地事务 + MQ 实现跨服务最终一致。

### 1.2 消息队列如何保证消息不丢失？

**答**：消息流转有三个环节，每个环节都可能丢，要分别保证。

**1. 生产者 → Broker**

- 同步发送 + 确认机制（ack）。生产者发完等 Broker 回 ACK 才算成功。
- 失败重试（注意要幂等）。
- 事务消息（如 RocketMQ）：先发预备消息，本地事务成功后再确认。

**2. Broker 持久化**

- 消息写入磁盘后才回 ACK（Kafka acks=all、RabbitMQ 持久化队列 + 持久化消息）。
- 多副本机制（Kafka 副本因子 ≥ 2，RabbitMQ 镜像队列）。
- 主从同步刷盘策略（同步 vs 异步）。

**3. Broker → 消费者**

- 手动 ACK（消费成功再 ACK，否则 Broker 重投递）。
- 关闭自动 ACK。
- 消费失败放入死信队列人工处理。

**记忆口诀**：生产端确认 + Broker 持久化 + 消费端手动 ACK 三位一体。

### 1.3 消息队列如何保证消息不重复消费（幂等性）？

**答**：

重复消费几乎不可避免（生产重试、Broker 重投递、网络抖动）。**幂等性**是业务层兜底——同一条消息处理多次和处理一次结果一致。

**实现方式**：

**1. 唯一 ID + 数据库唯一索引**

```sql
CREATE TABLE msg_consumed (
    msg_id VARCHAR(64) PRIMARY KEY,
    consume_time DATETIME
);
```

消费前 INSERT，主键冲突说明重复消费，直接跳过。

**2. Redis SETNX**

```
SET msg:id 1 NX EX 86400
```

成功才处理，已存在说明重复。

**3. 状态机**

业务对象有明确的状态流转。消息要求"从 A 状态变 B 状态"，如果对象已经是 B 或者不在 A，直接跳过。

**4. 版本号 / 乐观锁**

```sql
UPDATE order SET status=? WHERE id=? AND version=?
```

**5. 业务字段去重**

如订单号、流水号自带唯一性。

实践：核心业务用 1+3 双保险（数据库唯一约束 + 状态机校验）。

### 1.4 消息积压如何处理？

**答**：

**现象**：生产速度 >> 消费速度，Broker 里堆几百万条消息。

**根本原因 + 应对**：

**1. 消费者处理慢**

- 加消费者实例横向扩容
- 优化消费逻辑（去掉慢 SQL、批量处理、并行化）
- 增加消费者并发数（Kafka 的 partition 增加 + consumer 增加）

**2. 消费者 bug 卡住**

- 修复 bug 重启
- 卡住的消息放死信队列人工处理，不阻塞主流程

**3. 突发流量**

- 临时扩容下游消费者
- 限流上游生产者

**临时应急方案（百万级积压）**：

1. 修复消费 bug
2. **扩容 partition**（Kafka）或加 queue，比如从 4 个加到 32 个
3. 临时建一个**中转消费者**：只把消息转发到扩容后的新 topic，不处理业务
4. 在新 topic 上启动 32 个真实消费者并行消费
5. 积压消化完后下线中转，恢复原架构

**预防**：监控积压量，设阈值告警（如未消费 > 10 万条报警）。

### 1.5 如何保证消息的顺序性？

**答**：

**全局顺序**：整个 topic 只能有一个 partition + 一个消费者。**几乎不用**（吞吐太低）。

**局部顺序（业务常用）**：同一业务标识的消息按顺序消费。

**实现思路**：

**Kafka**：

- 同一个 key 的消息会被路由到同一 partition
- partition 内部是有序的
- 一个 partition 只被一个 consumer 消费
- 例：订单消息用 order_id 作 key，保证同一订单的消息顺序

**RocketMQ**：

- `MessageQueueSelector` 自定义路由，相同业务 ID 到同一队列
- 消费端用 `MessageListenerOrderly` 串行消费

**RabbitMQ**：

- 一个 queue + 一个 consumer（吞吐低）
- 或者上游分发时按 key 哈希到不同 queue，每个 queue 一个 consumer

**顺序消费的代价**：

- 一个 partition / queue 一个 consumer → 并发度受 partition 数限制
- 消费失败处理变复杂（不能简单跳过，否则破坏顺序）

### 1.6 死信队列是什么？有什么用？

**答**：

**死信队列（DLQ, Dead Letter Queue）**：存放无法被正常消费的消息的特殊队列。

**消息进死信的条件**：

- 消费失败超过重试次数（Kafka / RocketMQ 16 次）
- 消息被拒绝（RabbitMQ 的 `nack` + `requeue=false`）
- 消息超时未消费
- 队列长度超限被丢弃

**用途**：

1. **隔离故障消息**：不阻塞正常消费
2. **人工介入**：开发分析死信，定位问题
3. **延迟队列实现**：把消息放到带 TTL 的队列，过期后进死信，由死信消费者处理
4. **审计**：留存失败记录

**配置**：

- RabbitMQ：声明 queue 时指定 `x-dead-letter-exchange` 和 `x-dead-letter-routing-key`
- RocketMQ：自动创建 `%DLQ%消费者组名` 队列

### 1.7 延迟消息如何实现？

**答**：

业务场景：订单 30 分钟未支付自动关闭、定时推送等。

**方案 1：RabbitMQ TTL + 死信队列**

```
消息发到 队列A（设 TTL）
TTL 到期未消费 → 进死信交换机
死信交换机 → 路由到 队列B（实际处理队列）
消费者监听 队列B
```

或者用 `rabbitmq-delayed-message-exchange` 插件直接支持。

**方案 2：RocketMQ 原生延迟级别**

RocketMQ 支持 18 个固定延迟级别（1s/5s/10s/30s/1m/...2h）：

```java
msg.setDelayTimeLevel(3);  // 10 秒后投递
```

RocketMQ 5.0+ 支持任意秒级延迟。

**方案 3：Kafka + 时间轮 / 自定义实现**

Kafka 不原生支持。常见方案：

- 生产者把消息写到 `delay_topic`
- 单独服务消费 `delay_topic`，把消息放进时间轮
- 时间到了再转发到真实 topic

**方案 4：Redis ZSet**

```
ZADD delay_queue <execute_at_ms> msg
ZRANGEBYSCORE delay_queue 0 <now>
```

适合小规模延迟任务，不适合海量。

**方案 5：定时扫表**

DB 存延迟任务，定时任务每秒扫描到期任务。简单但扫描压力大。

### 1.8 消息队列的推模式和拉模式的区别？

**答**：

**推模式（Push）**：Broker 主动把消息推送给消费者。

- 优点：实时性好，消费延迟低
- 缺点：消费者处理能力不一致时容易压垮慢消费者；Broker 需要维护消费者状态

**拉模式（Pull）**：消费者主动从 Broker 拉取消息。

- 优点：消费者按自己节奏消费，不会被压垮；Broker 实现简单
- 缺点：拉取频率不好控制（频繁拉浪费，间隔大延迟高）

**对比**：

| | 推模式 | 拉模式 |
|:---|:---|:---|
| 实时性 | 高 | 看拉取频率 |
| 流量控制 | 难 | 容易 |
| Broker 状态 | 复杂 | 简单 |
| 网络空转 | 少 | 多（没消息也拉）|

**实际产品**：

- Kafka：拉模式
- RabbitMQ：推为主，Pull API 也有
- RocketMQ：长轮询（Pull 模式 + Broker 端等待，兼顾两者优点）

**长轮询**：消费者发拉请求，Broker 没有消息时不立即返回，挂起一段时间。期间有新消息就立即返回；超时还没有就返回空。这样既不空转也实时。

---

## 二、RabbitMQ

### 2.1 RabbitMQ 的核心组件有哪些？（Exchange、Queue、Binding）

**答**：

```
Producer → Exchange → (Binding) → Queue → Consumer
```

- **Producer**：消息生产者
- **Exchange（交换机）**：接收消息，按规则路由到 Queue。不存储消息
- **Queue（队列）**：存储消息，等待消费
- **Binding（绑定）**：Exchange 和 Queue 之间的关联规则
- **Routing Key**：消息携带的路由键
- **Binding Key**：Exchange 与 Queue 绑定时的键
- **Channel**：连接内的虚拟通道，避免频繁创建 TCP 连接
- **Connection**：TCP 连接
- **VHost**：虚拟主机，逻辑隔离不同业务

**消息流转**：生产者发送消息时带上 `exchange + routingKey` → Exchange 匹配 binding → 路由到对应 Queue → 消费者订阅 Queue 拉取消息。

### 2.2 Exchange 的四种类型（direct、fanout、topic、headers）

**答**：

**1. Direct**

精确匹配。`routingKey == bindingKey` 才路由。

```
Exchange E1 (direct)
├── binding "info" → Queue Q1
├── binding "error" → Queue Q2

发送 routingKey="error" → 进 Q2
发送 routingKey="info" → 进 Q1
```

**2. Fanout**

广播。忽略 routingKey，所有绑定的 Queue 都收一份。

```
Exchange E1 (fanout)
├── Queue Q1
├── Queue Q2

发送任意消息 → Q1 和 Q2 都收到
```

适合广播通知。

**3. Topic**

模式匹配。bindingKey 支持通配符：

- `*`：匹配一个单词
- `#`：匹配零个或多个单词

```
Exchange E1 (topic)
├── binding "*.error" → Queue Q1
├── binding "service.#" → Queue Q2

发送 routingKey="db.error" → Q1（匹配 *.error）
发送 routingKey="service.user.login" → Q2（匹配 service.#）
```

最灵活，日常用得最多。

**4. Headers**

按消息 headers 字段匹配，不用 routingKey。性能较差，**生产很少用**。

### 2.3 RabbitMQ 如何保证消息可靠性？

**答**：三段都要保证。

**1. 生产者 → Broker**

- **publisher-confirm 模式**：消息到达 Exchange 后 Broker 回 ACK
- **publisher-return 模式**：消息从 Exchange 路由不到任何 Queue 时 Broker 回退
- **事务模式**：性能差，不推荐

```yaml
spring.rabbitmq:
  publisher-confirm-type: correlated   # 异步确认
  publisher-returns: true              # 启用 return 回调
```

**2. Broker 持久化**

- **Exchange 持久化**：声明时 `durable=true`
- **Queue 持久化**：声明时 `durable=true`
- **消息持久化**：发送时 `deliveryMode=2`

三者缺一不可。Broker 重启后 Exchange/Queue 还在，且消息能从磁盘恢复。

注意：持久化消息也只是写到磁盘 buffer，机器宕机仍可能丢一点。要严格保证用**镜像队列**。

**3. Broker → 消费者**

- 关闭自动 ACK：`spring.rabbitmq.listener.simple.acknowledge-mode: manual`
- 消费成功后手动 ACK：`channel.basicAck(deliveryTag, false)`
- 失败时 nack 让消息重投递：`channel.basicNack(deliveryTag, false, true)`
- 多次失败的消息放死信队列

### 2.4 RabbitMQ 的死信队列和延迟队列如何实现？

**答**：

**死信队列**

消息变死信的三种情况：

1. 被 `basicNack` 且 `requeue=false`
2. 消息 TTL 过期
3. 队列长度超过 `x-max-length`

声明队列时指定死信交换机：

```java
Map<String, Object> args = new HashMap<>();
args.put("x-dead-letter-exchange", "dlx.exchange");
args.put("x-dead-letter-routing-key", "dlx.key");

channel.queueDeclare("normal.queue", true, false, false, args);
```

**延迟队列方案 1：TTL + DLX**

```
Queue A (TTL=30000ms)  →  TTL 到期变死信  →  Exchange DLX  →  Queue B（实际消费）
```

发到 Queue A 但不消费，消息 TTL 过期自动转到 Queue B。

**缺点**：同一队列消息 TTL 必须一样（队头阻塞）。

**延迟队列方案 2：rabbitmq-delayed-message-exchange 插件**

安装插件后声明 `x-delayed-message` 类型 Exchange。发送时设 header `x-delay=30000` 即可。每条消息独立延迟，更灵活。

### 2.5 RabbitMQ 的集群模式有哪些？镜像队列是什么？

**答**：

**1. 普通集群（默认）**

多节点共享元数据（Exchange / Queue 定义），**但消息只存在创建队列的那个节点上**。其他节点收到消费请求会"转发"到队列所在节点。

- 优点：横向扩展
- 缺点：队列所在节点宕机 → 该队列不可用 + 消息可能丢失

**2. 镜像队列（Mirror Queue）**

队列在多个节点上同步副本。一个主（master）+ 多个从（mirror）。主写从同步，主挂了从自动顶上。

```bash
rabbitmqctl set_policy ha-all "^" '{"ha-mode":"all"}'
```

- 优点：高可用
- 缺点：写性能下降（要同步副本）；网络分区时可能脑裂

**3. Quorum Queue（3.8+ 推荐）**

基于 Raft 的强一致队列。镜像队列的现代替代。

- 优点：脑裂自动处理；数据更安全
- 缺点：不支持优先级、TTL 等部分特性

**4. Streams（3.9+）**

类似 Kafka 的日志流。适合海量消息持久化。

### 2.6 RabbitMQ 的 prefetch 是什么？如何做流量控制？

**答**：

**prefetch（预取）**：Broker 一次性给消费者推送多少条**未 ACK** 的消息。

```java
channel.basicQos(10);  // 一次最多预取 10 条
```

**作用**：

- prefetch=0（不限制）：Broker 把所有消息推给消费者，慢消费者被压垮
- prefetch=1：处理完一条 ACK 后才推下一条，公平但吞吐低
- prefetch=10-100：平衡，常用值

**流量控制三层**：

1. **prefetch**：消费者侧。控制单消费者并发处理量
2. **连接级流控**：Broker 内存 / 磁盘超阈值时阻塞发布者
3. **队列长度限制**：`x-max-length` 满了拒收或丢弃

**经验值**：消费者处理一条消息平均 100ms，prefetch 设 20-50 比较合理；处理慢（秒级）设 1-5。

---

## 三、Kafka

### 3.1 Kafka 的架构是什么？

**答**：

```
Producer →   Broker Cluster   ← Consumer Group
              ↑
         ZooKeeper / KRaft
```

**核心组件**：

- **Producer**：消息生产者
- **Broker**：Kafka 节点。多个 Broker 组成集群
- **Topic**：逻辑消息主题
- **Partition**：Topic 的物理分区。一个 Topic 有多个 Partition 分布到不同 Broker
- **Replica**：Partition 的副本。一个 leader + N 个 follower
- **Consumer**：消息消费者
- **Consumer Group**：消费者组。同组内消息只被一个消费者消费；不同组互不影响
- **ZooKeeper**（旧）：存元数据、Controller 选举、Broker 注册
- **KRaft**（Kafka 3.0+）：取代 ZooKeeper

**消息存储**：

- 每个 partition 对应一个目录
- 目录内多个 .log 文件（segment），追加写
- 配套 .index（偏移量索引）和 .timeindex（时间索引）

### 3.2 Kafka 为什么吞吐量高？

**答**：

**1. 顺序写磁盘**

数据按 partition 追加到 .log 文件末尾。磁盘顺序写速度接近内存随机写（机械盘 100MB/s，SSD 几 GB/s）。

**2. Page Cache**

Broker 不维护应用层缓存，直接利用操作系统 Page Cache。写入先到 Page Cache，OS 异步刷盘。读取时如果在 Page Cache 命中，零磁盘 IO。

**3. 零拷贝（Zero-Copy）**

传统流程：磁盘 → 内核缓冲 → 应用 buffer → Socket buffer → 网卡（4 次拷贝 + 4 次上下文切换）。

Kafka 用 `sendfile` 系统调用：磁盘 → 内核缓冲 → 网卡（2 次拷贝 + 2 次上下文切换）。

**4. 批量发送**

Producer 累积一批消息再发：

```yaml
batch.size: 16384       # 16KB 满了就发
linger.ms: 10           # 或最多等 10ms
```

减少网络往返次数。

**5. 数据压缩**

支持 gzip / snappy / lz4 / zstd 压缩。压缩后传输 + 存储成本下降，CPU 略增。

**6. 分区并行**

一个 Topic 多个 Partition 分散到多个 Broker，Producer 和 Consumer 都能并行处理。

### 3.3 Kafka 的分区和副本机制是什么？

**答**：

**分区（Partition）**：

- 一个 Topic 拆成多个 Partition
- 每个 Partition 是有序的日志
- 不同 Partition 之间不保证顺序
- Partition 数 = Topic 的并发上限

**副本（Replica）**：

- 每个 Partition 有多个副本（replication.factor 通常 3）
- 一个 **Leader** + N 个 **Follower**
- Producer / Consumer 只跟 Leader 交互
- Follower 从 Leader 异步拉取数据保持同步

**Leader 选举**：

- ZK 模式下 Controller 负责
- Follower 副本同步状态满足条件（在 ISR 中）才能当选

**Partition 放置**：尽量分散到不同 Broker，避免单点压力。

### 3.4 Kafka 的消费者组是什么？再均衡（Rebalance）是什么？

**答**：

**消费者组（Consumer Group）**：

- 同组内**每个 Partition 只被一个消费者消费**（消费者数 ≤ Partition 数）
- 不同组之间互不影响（同一条消息会被多个组各消费一次）

```
Topic（4 个 Partition）
├── Group A
│   ├── Consumer A1: 消费 P0, P1
│   └── Consumer A2: 消费 P2, P3
├── Group B
│   ├── Consumer B1: 消费 P0, P1, P2
│   └── Consumer B2: 消费 P3
```

**再均衡（Rebalance）**：组内消费者数量变化时，重新分配 Partition。

**触发条件**：

1. 消费者加入 / 离开组
2. 消费者宕机（心跳超时）
3. Topic 增加 Partition
4. 订阅的 Topic 列表变化

**问题**：Rebalance 期间整个组**停止消费**（STW），可能持续几秒到几分钟。

**优化**：

- 调小 `session.timeout.ms` 让宕机快速感知（但太短易误判）
- 调大 `max.poll.interval.ms` 避免消费慢被踢出
- 用 **静态成员（Static Membership）**：3.0+ 引入，配 group.instance.id，短暂宕机不触发 rebalance
- 用 **Cooperative Rebalance**：增量 rebalance，只迁移变化的 Partition

### 3.5 Kafka 如何保证消息不丢失？（acks 参数）

**答**：

**Producer 端 acks 参数**：

- `acks=0`：发完就走，不等确认。**最快，丢失风险最大**
- `acks=1`：Leader 写成功就回。**Leader 挂了未同步到 Follower 的丢失**
- `acks=-1` / `acks=all`：所有 ISR 副本都同步才回。**最安全**

配套配置：

```yaml
acks: all
retries: 2147483647        # 一直重试直到成功
max.in.flight.requests.per.connection: 1   # 严格顺序（牺牲吞吐）
enable.idempotence: true   # 幂等生产
```

**Broker 端**：

- `replication.factor >= 3`：至少 3 副本
- `min.insync.replicas >= 2`：至少 2 个副本在 ISR 才接受写入
- `unclean.leader.election.enable=false`：不允许 ISR 外的副本当 Leader（牺牲可用性保数据一致）

**Consumer 端**：

- `enable.auto.commit=false`：关闭自动提交 offset
- 处理完业务后手动 `commitSync()`

三端都做好，可以做到几乎不丢消息（除非整个集群全挂）。

### 3.6 Kafka 的 offset 管理机制？

**答**：

**offset**：消息在 Partition 内的唯一序号，从 0 递增。

**两种 offset**：

- **Partition offset**：Broker 维护，每条消息的序号
- **Consumer offset**：消费到哪里了

**Consumer offset 存储**：

- Kafka 0.9 之前：存 ZooKeeper（频繁写 ZK 压力大）
- 0.9+：存到内置 Topic `__consumer_offsets`

**提交方式**：

- **自动提交**：`enable.auto.commit=true`，每 `auto.commit.interval.ms` 提交一次。**可能丢消息或重复消费**
- **手动同步提交**：`commitSync()` 同步等返回
- **手动异步提交**：`commitAsync()` 不等返回，性能好但失败处理麻烦
- **按消息提交**：每条 ACK，性能差

**offset 重置策略**：消费者首次启动或 offset 失效时：

- `auto.offset.reset=latest`：从最新开始（默认，可能丢历史）
- `auto.offset.reset=earliest`：从最早开始（可能重复）
- `auto.offset.reset=none`：抛异常

### 3.7 Kafka 的 ISR、OSR、AR 分别是什么？

**答**：

- **AR（Assigned Replicas）**：所有副本（Leader + 全部 Follower）
- **ISR（In-Sync Replicas）**：与 Leader 保持同步的副本集合
- **OSR（Out-of-Sync Replicas）**：与 Leader 同步落后或失联的副本

公式：`AR = ISR + OSR`

**判断 Follower 是否在 ISR**：`replica.lag.time.max.ms`（默认 30 秒）。Follower 在这段时间内没追上 Leader 就被踢出 ISR，追上后重新加入。

**ISR 的作用**：

- `acks=all` 时只要 ISR 全部同步就算成功（不要求 OSR）
- Leader 选举只从 ISR 选（除非 `unclean.leader.election.enable=true`）

### 3.8 Kafka 如何实现精确一次（Exactly Once）语义？

**答**：

三种语义：

- **At Most Once**（最多一次）：可能丢，不重复
- **At Least Once**（至少一次）：不丢，可能重复
- **Exactly Once**（精确一次）：不丢不重

默认 at least once。**精确一次需要两步**：

**1. Producer 幂等性**

```yaml
enable.idempotence: true
```

Producer 给每条消息分配 `<PID, Partition, Seq>`，Broker 去重。同一条消息重试不会写两遍。

**2. 事务**

```yaml
transactional.id: my-tx-id
```

Producer 把"发消息 + 提交 offset"包在一个事务里，要么全成功要么全失败。常用于 Kafka Streams 的"读 - 处理 - 写"模式。

**限制**：

- 跨 Kafka 之外的系统（如写 DB）做不到精确一次，只能 at least once + 业务幂等
- 性能比 at least once 低

### 3.9 Kafka 和 RabbitMQ 的区别及各自适用场景？

**答**：

| 维度 | Kafka | RabbitMQ |
|:---|:---|:---|
| 定位 | 分布式日志流 | 传统消息中间件 |
| 吞吐量 | 极高（百万 TPS）| 万级 TPS |
| 延迟 | 毫秒级 | 微秒级 |
| 消息可靠性 | 高 | 高（镜像/Quorum）|
| 消息顺序 | Partition 内有序 | 单 Queue 有序 |
| 消息广播 | 多 Consumer Group | Fanout Exchange |
| 消息回溯 | 支持（按 offset / 时间） | 不支持 |
| 路由灵活性 | 弱（只有 Topic）| 强（4 种 Exchange）|
| 消息保留 | 按时间 / 大小（可保留几天）| 消费后删 |
| 协议 | 自定义二进制 | AMQP / MQTT / STOMP |
| 适用场景 | 日志、大数据、流处理 | 业务异步、复杂路由 |

**选型**：

- 日志收集、用户行为、binlog 同步、监控指标 → Kafka
- 订单、邮件、通知、复杂业务路由 → RabbitMQ / RocketMQ
- 两个都要的场景，RocketMQ 兼顾（蚂蚁等大厂偏好）

### 3.10 Kafka 的 Controller 是什么？选举机制是什么？

**答**：

**Controller**：Kafka 集群中的"管理者"。所有 Broker 都竞争成为 Controller，但同时只有一个。

**职责**：

- 维护 Partition Leader 状态
- Partition Leader 选举
- 处理 Broker 上下线
- 处理 Topic 创建 / 删除
- 处理 Partition 重新分配

**选举（ZooKeeper 模式）**：

1. 所有 Broker 启动时尝试在 ZK 创建 `/controller` 临时节点
2. 创建成功的成为 Controller
3. 其他 Broker 监听该节点
4. Controller 宕机 → ZK 临时节点消失 → 其他 Broker 重新竞争

**KRaft 模式**：用 Raft 共识算法选 Controller，不再依赖 ZK。

### 3.11 Kafka 3.0 之后去掉 ZooKeeper 的 KRaft 模式？

**答**：

**为什么去掉 ZK**：

1. **运维复杂**：要维护两套集群（Kafka + ZK）
2. **性能瓶颈**：元数据存 ZK，大集群（万级 Partition）启动慢、元数据变更慢
3. **架构耦合**：依赖外部协调服务
4. **协议不一致**：Kafka 是 Pull + 异步，ZK 是 ZAB 强一致

**KRaft 方案**：

- 用 Raft 算法在 Kafka 内部实现元数据存储和共识
- 一组 Controller 节点（通常 3-5 个）组成 Raft 集群
- 元数据存在 `__cluster_metadata` 内部 Topic
- Broker 从 Controller 拉取元数据更新

**优势**：

- 单一系统，部署简单
- 元数据规模可达百万 Partition（ZK 模式约 20 万）
- 故障恢复更快
- 性能更好

**时间线**：

- Kafka 2.8（2021）：KRaft 早期预览
- Kafka 3.0（2021）：KRaft 生产可用
- Kafka 3.3：默认推荐 KRaft
- Kafka 4.0（预计）：彻底移除 ZK 支持

---

## 四、RocketMQ

### 4.1 RocketMQ 的架构是什么？NameServer 的作用？

**答**：

```
Producer  ↔  NameServer Cluster  ↔  Broker Cluster (master + slave)  ↔  Consumer
```

**四大组件**：

- **Producer**：消息生产者
- **Consumer**：消费者
- **Broker**：消息存储 + 转发节点。一个 master + 多个 slave
- **NameServer**：轻量级注册中心

**NameServer 的作用**：

1. Broker 启动时向所有 NameServer 注册自己
2. 维护 Topic 路由信息（Topic 在哪些 Broker 的哪些 Queue 上）
3. Producer / Consumer 启动时从 NameServer 拉取路由信息
4. Producer / Consumer 定期（默认 30 秒）刷新本地路由

**NameServer 特点**：

- **无状态**：节点之间互不通信，无主从
- **去中心化**：客户端连任一个都可以
- **轻量**：比 ZK 简单很多

跟 ZK 区别：NameServer 不保证强一致（节点间元数据可能有短暂不一致），但简单可靠。

### 4.2 RocketMQ 的事务消息原理是什么？

**答**：

业务场景：本地事务和 MQ 发送要保证一致性（先发 MQ 还是先做业务都有问题）。

**RocketMQ 事务消息**两阶段：

**阶段 1：发送半消息**

1. Producer 发送"半消息（half message）"到 Broker
2. Broker 持久化但**对 Consumer 不可见**
3. Producer 收到确认

**阶段 2：执行本地事务**

4. Producer 执行本地事务（如更新数据库）
5. 根据本地事务结果发 commit / rollback 给 Broker
6. Broker 收到 commit → 半消息变为正常消息可被消费
7. Broker 收到 rollback → 删除半消息

**阶段 3：事务回查（兜底）**

如果阶段 2 的 commit / rollback 因为 Producer 宕机没发出去：

8. Broker 定期回查 Producer："这条半消息对应的本地事务是什么状态？"
9. Producer 检查本地事务表，回复 commit / rollback / 未知（未知则继续回查）

**实现要点**：

- Producer 必须实现 `TransactionListener` 接口
- 本地事务和 MQ 状态要可查（一般用本地事务消息表）

### 4.3 RocketMQ 如何实现延迟消息？

**答**：

**4.x 版本**：18 个固定延迟级别。

```
级别 1 = 1s
级别 2 = 5s
级别 3 = 10s
级别 4 = 30s
级别 5 = 1m
...
级别 18 = 2h
```

```java
Message msg = new Message("topic", "tag", body);
msg.setDelayTimeLevel(3);  // 10 秒后
```

**实现机制**：

1. 投递的消息 topic 被替换为 `SCHEDULE_TOPIC_XXXX`
2. 按延迟级别分到对应队列
3. 内部定时任务扫描，到期后转回原 topic

**5.x 版本**：支持任意秒级延迟。

```java
msg.setDeliverTimeMs(System.currentTimeMillis() + 90000);
```

底层用 TimerWheel（时间轮）实现，性能比 4.x 强。

### 4.4 RocketMQ 的顺序消息如何保证？

**答**：

**分区有序（局部有序）**：同一业务 ID 的消息按顺序消费。

**生产端**：

```java
SendResult result = producer.send(msg, new MessageQueueSelector() {
    @Override
    public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
        Long orderId = (Long) arg;
        return mqs.get((int) (orderId % mqs.size()));
    }
}, orderId);
```

把相同 orderId 的消息送到同一 Queue。

**消费端**：

```java
consumer.registerMessageListener(new MessageListenerOrderly() {
    public ConsumeOrderlyStatus consumeMessage(...) {
        // 同一 Queue 串行消费
    }
});
```

`MessageListenerOrderly` 保证同一 Queue 单线程顺序消费。

**注意**：

- 顺序消费时消费失败会**反复重试**（不能跳过否则破坏顺序）
- 如果某条消息一直失败，整个 Queue 会被阻塞
- 关键业务要配合死信队列 + 监控

### 4.5 RocketMQ 和 Kafka 的区别？

**答**：

| 维度 | RocketMQ | Kafka |
|:---|:---|:---|
| 出身 | 阿里 → Apache | LinkedIn → Apache |
| 协议 | 自定义 | 自定义 |
| 注册中心 | NameServer（轻量）| ZK / KRaft |
| 单机吞吐 | 10 万 TPS | 100 万 TPS |
| 主从复制 | 同步 / 异步可选 | ISR 异步 |
| 延迟消息 | 原生支持 | 不支持（需自己实现） |
| 事务消息 | 原生支持 | 0.11+ 支持（不同语义） |
| 顺序消息 | 原生支持 | Partition 内有序 |
| 消息回溯 | 支持 | 支持 |
| 消息过滤 | Broker 端过滤（tag/SQL92）| Consumer 端过滤 |
| 死信队列 | 原生 | 不原生 |
| 适用场景 | 业务消息（订单、交易）| 日志、流处理 |

**选型**：

- 业务消息 + 需要事务/延迟/顺序 → RocketMQ
- 日志 / 大数据 / 流处理 / 极致吞吐 → Kafka
- 简单异步 + 复杂路由 → RabbitMQ

---

## 附录：高频实战场景

### A.1 重要业务消息怎么设计 100% 不丢？

**答**：

完整链路：

```
Producer 端：
- 同步发送 + acks=all
- 失败重试 3 次
- 重试失败的消息写本地表（兜底，由定时任务重发）

Broker 端：
- 多副本（3 副本）
- 同步刷盘（性能换可靠）
- min.insync.replicas=2

Consumer 端：
- 关闭自动 ACK
- 业务处理完成后手动 ACK
- 失败的消息进死信队列
- 业务幂等（基于唯一 ID 去重）

监控：
- 积压量监控
- 死信队列告警
- 重试失败告警
```

### A.2 消息一致性如何保证？跨服务事务怎么做？

**答**：

**本地消息表**方案最常用：

```
1. 业务表 + 消息表 在同一个数据库
2. 业务操作 + INSERT 消息（同一事务，保证一致）
3. 定时任务扫描消息表，发送到 MQ
4. 消费方处理 + 业务幂等
5. 发送成功的消息从消息表删除（或标记）
```

特点：

- 100% 最终一致
- 不依赖 MQ 的事务特性
- 业务侵入小

**RocketMQ 事务消息**是上述思路的内置实现。

### A.3 MQ 选型决策树

```
是否需要事务/延迟/顺序原生支持？
├── 是 → RocketMQ
└── 否
    ├── 吞吐量需求是否极高（10 万 TPS+）？
    │   ├── 是 → Kafka
    │   └── 否
    │       ├── 路由规则是否复杂？
    │       │   ├── 是 → RabbitMQ
    │       │   └── 否
    │       │       └── 消息量小且只用做异步？→ Redis Stream
```
