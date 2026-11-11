# 八股文 · Redis

> 覆盖基础概念、持久化、缓存问题、高可用、分布式、进阶六大主题。

---

## 一、基础概念

### 1.1 Redis 为什么这么快？

**答**：

1. **基于内存**：所有数据在内存里操作，对比磁盘 IO 快几个数量级
2. **单线程模型**：无线程切换、无锁开销
3. **IO 多路复用**：基于 epoll（Linux）/ kqueue（macOS），单线程处理万级并发连接
4. **高效数据结构**：每种数据类型都有针对场景优化的底层结构
5. **C 语言实现**：贴近系统调用，无虚拟机开销
6. **避免上下文切换**：所有命令在主线程顺序执行

实测单实例 QPS 10 万级别（普通命令）。

### 1.2 Redis 是单线程的吗？为什么单线程还这么快？

**答**：

Redis 的"单线程"指的是**处理客户端请求**的核心命令执行是单线程。但 Redis 一直是多线程：

- 持久化（RDB / AOF）：fork 子进程
- 主从同步：单独线程
- 慢 IO（如 unlink、flushdb async）：4.0+ 用后台线程
- 6.0+：网络 IO 多线程，命令执行仍单线程

**单线程为什么不慢**：

1. 瓶颈不在 CPU，在网络和内存
2. 单线程避免了锁竞争和上下文切换
3. IO 多路复用一个线程也能处理大量连接
4. 简单（无并发问题，代码易维护）

注意：单线程意味着**任何一条慢命令都会阻塞整个 Redis**。所以禁止用 `KEYS *`、`FLUSHALL`、`HGETALL` 大 hash 等。

### 1.3 Redis 6.0 引入多线程的原因是什么？

**答**：

随着 SSD / 千兆万兆网卡普及，单线程网络 IO 成了瓶颈：

- 单线程 read / write 系统调用占用 CPU
- 解析 RESP 协议占用 CPU
- 多核机器只用一个核浪费

**6.0 的多线程**：把"网络 IO 读写 + 协议解析"用多线程做，**命令执行仍然单线程**。

| 阶段 | 6.0 之前 | 6.0+ |
|:---|:---|:---|
| 接收请求（read）| 主线程 | IO 线程 |
| 解析协议 | 主线程 | IO 线程 |
| 执行命令 | 主线程 | 主线程 |
| 写回结果（write）| 主线程 | IO 线程 |

启用：`io-threads 4` + `io-threads-do-reads yes`。一般 4-8 个 IO 线程即可，超过没明显收益。

### 1.4 Redis 有哪些数据类型？各自的底层实现是什么？

**答**：

| 类型 | 用途 | 底层实现 |
|:---|:---|:---|
| String | 字符串 / 计数器 / 序列化对象 | SDS（简单动态字符串） |
| List | 列表 / 队列 | listpack（短）/ quicklist（长）|
| Hash | 哈希表 / 对象 | listpack（少）/ hashtable（多）|
| Set | 集合 / 去重 / 标签 | listpack（int 少）/ intset / hashtable |
| ZSet | 有序集合 / 排行榜 | listpack（少）/ skiplist + hashtable |
| Bitmap | 位图 | String |
| HyperLogLog | 基数统计 | String（特殊编码）|
| GEO | 地理位置 | ZSet |
| Stream | 消息流 | radix tree + listpack |

**编码切换阈值**（可配）：

- Hash listpack → hashtable：>128 元素 或 单个 value >64 字节
- ZSet listpack → skiplist：>128 元素 或 元素 >64 字节
- Set intset → hashtable：>512 元素 或 非整数

Redis 7.0 用 listpack 取代了 ziplist（解决 ziplist 的连锁更新问题）。

### 1.5 String 的底层实现 SDS 是什么？

**答**：SDS（Simple Dynamic String），相比 C 字符串的改进：

```c
struct sdshdr {
    int len;       // 已使用长度
    int alloc;     // 总分配长度
    unsigned char flags;
    char buf[];    // 实际数据
};
```

**优势**：

1. **O(1) 求长度**：直接读 len，不用遍历
2. **二进制安全**：可以存任何字节（C 字符串遇 `\0` 截断）
3. **避免缓冲区溢出**：API 自动判断空间是否够，不够会自动扩容
4. **空间预分配**：扩容时多分配一些，减少未来分配次数
5. **惰性空间释放**：缩短不立即缩内存，记录在 alloc，下次复用

不同长度的 String 用不同结构（sdshdr5/8/16/32/64）省内存。

### 1.6 ZSet 什么时候用跳表，什么时候用压缩列表？

**答**：

| 条件 | 编码 |
|:---|:---|
| 元素数 ≤ 128 **且** 元素长度 ≤ 64 字节 | listpack（紧凑数组）|
| 任一条件超出 | skiplist + hashtable |

阈值配置：`zset-max-listpack-entries 128` / `zset-max-listpack-value 64`。

**skiplist + hashtable 双结构**：

- skiplist 维护排序，支持 ZRANGEBYSCORE / ZRANK 等范围操作 O(log N)
- hashtable 存 member → score 映射，支持 ZSCORE O(1)

**为什么 ZSet 用跳表而不是红黑树**：

1. 跳表实现更简单（红黑树旋转复杂）
2. 跳表范围查询天然友好（链表直接遍历）
3. 跳表内存访问局部性更好

### 1.7 Redis 的 key 过期策略有哪些？

**答**：Redis 同时使用三种策略：

1. **定时删除（Timed Deletion）**：设过期时间时同时设定时器，到期触发删除。**精准但 CPU 开销大**，Redis 不用。
2. **惰性删除（Lazy Deletion）**：访问 key 时检查是否过期，过期则删除并返回 nil。**省 CPU 但占内存**（不访问就不删）。
3. **定期删除（Periodic Deletion）**：每隔一段时间随机抽取一批 key 检查过期。**平衡 CPU 和内存**。

**Redis 实际**：惰性 + 定期组合使用。

参数：

- `hz`：每秒后台任务频率，默认 10
- 定期任务每次扫描 20 个 key，过期比例超 25% 继续扫，否则下次

不足：被动检查可能让大量过期 key 留在内存。当内存接近上限时触发**主动淘汰**。

### 1.8 Redis 内存淘汰策略有哪些？

**答**：当内存达到 `maxmemory` 时触发。

| 策略 | 范围 | 算法 |
|:---|:---|:---|
| noeviction | - | 不淘汰，写入报错（默认）|
| allkeys-lru | 所有 key | 最近最少使用 |
| volatile-lru | 设置了过期的 key | 最近最少使用 |
| allkeys-lfu | 所有 key | 最少使用次数（4.0+）|
| volatile-lfu | 设置了过期的 key | LFU |
| allkeys-random | 所有 key | 随机 |
| volatile-random | 设置了过期的 key | 随机 |
| volatile-ttl | 设置了过期的 key | 越接近过期越先淘汰 |

**选择**：

- 缓存场景：`allkeys-lru` 或 `allkeys-lfu`（LFU 对长尾热点更好）
- 持久化数据 + 缓存混合：`volatile-lru`（只淘汰带过期的 key）
- 业务严格不允许丢：`noeviction`，且监控容量

**Redis 的 LRU 是近似 LRU**：每次随机抽样 N 个 key 淘汰最久未用的，N 由 `maxmemory-samples` 控制（默认 5）。

---

## 二、持久化

### 2.1 RDB 和 AOF 的区别是什么？

**答**：

| | RDB | AOF |
|:---|:---|:---|
| 内容 | 内存快照（二进制）| 命令追加（文本）|
| 触发 | 定时 / 手动 SAVE/BGSAVE | 每次写命令 |
| 文件大小 | 小（压缩）| 大 |
| 恢复速度 | 快（直接载入内存）| 慢（重放命令）|
| 数据安全 | 最近一次快照后的数据可能丢 | 最多丢 1 秒 |
| 主进程影响 | fork 时短暂阻塞 | 持续写日志，对主进程影响小 |

**RDB 适合**：备份、灾难恢复、Slave 全量同步
**AOF 适合**：数据安全要求高

生产建议：**RDB + AOF 混合**。

### 2.2 RDB 的 fork 过程和 COW（写时复制）机制？

**答**：

执行 `BGSAVE` 时：

1. Redis 主进程调用 `fork()` 创建子进程
2. 子进程拥有主进程内存的"快照视图"
3. 子进程把内存数据写入 RDB 文件
4. 主进程继续处理客户端请求

**COW（Copy-On-Write）**：

- fork 时父子进程**共享物理内存页**（只复制页表）
- 任一方**写**某个页时，操作系统才**复制**这个页（写者独占新页）
- 大部分页只读共享，节省内存

**风险**：

- 如果 fork 期间主进程大量写入，会触发大量 COW，内存可能瞬间翻倍
- fork 本身是系统调用，对大内存实例（几十 GB）可能耗时几秒，期间主进程阻塞

**优化**：

- 限制单实例内存（10GB 以内）
- 关闭 THP（Transparent Huge Pages，会让 COW 变慢）
- 高写入场景考虑 AOF 而不是 RDB

### 2.3 AOF 重写的触发条件和原理？

**答**：

AOF 文件持续追加会越来越大。重写就是**生成一个最小的 AOF 文件**，效果等同于当前内存状态。

**触发**：

- 手动：`BGREWRITEAOF`
- 自动：`auto-aof-rewrite-percentage 100` + `auto-aof-rewrite-min-size 64mb`（文件大小超过基准 100% 且 > 64MB）

**原理**：

1. 主进程 fork 子进程
2. 子进程遍历内存数据，把每个 key 用最少命令重写到新 AOF
3. 重写期间，主进程把新写命令同时追加到 **AOF 缓冲区** 和 **AOF 重写缓冲区**
4. 子进程完成后，主进程把"重写缓冲区"内容追加到新 AOF
5. 用新 AOF 替换旧 AOF

**Redis 7.0 AOF 重写**：用 multi-part AOF（拆成 base + incr 多个文件），重写时无需复制内存中的命令，更高效。

### 2.4 RDB + AOF 混合持久化是什么？

**答**：Redis 4.0+ 引入。开启 `aof-use-rdb-preamble yes`。

**原理**：AOF 重写时，先用 RDB 格式写当前内存快照作为"base"，之后的增量命令用 AOF 格式追加。

**优势**：

- 恢复时先快速载入 RDB 部分
- 再重放最近的 AOF 增量
- 兼顾速度和数据完整性

**文件结构**：

```
| RDB 二进制头 + 数据 | AOF 文本命令 |
```

### 2.5 Redis 重启后数据恢复的流程？

**答**：

启动时按优先级选择：

1. 开了 AOF → 用 AOF（数据更全）
2. 没 AOF 但有 RDB → 用 RDB
3. 都没有 → 空数据库

**恢复细节**：

- AOF：逐行重放命令到内存
- 混合 AOF：先载入 RDB 部分（快），再重放 AOF 增量
- 中途出错：默认拒绝启动，可设 `aof-load-truncated yes` 容错

大内存实例恢复要几分钟。这段时间无法对外服务。

---

## 三、缓存问题

### 3.1 什么是缓存穿透？如何解决？

**答**：

**穿透**：查询一个**根本不存在**的数据，缓存中没有，请求每次都打到数据库。常见于恶意攻击（构造大量不存在的 key）。

**解决方案**：

1. **缓存空值**：DB 查不到也缓存 `null`，TTL 短（5 分钟）防止占内存太久
2. **布隆过滤器**：在缓存前加一层，预先把所有有效 key 哈希到 bit 数组。查询时先过布隆，没有就直接拒绝
3. **接口层校验**：参数格式、用户权限校验拦截非法请求
4. **限流**：对同一来源做请求限流

布隆过滤器有**误判率**（有可能说有但实际没有），无漏判（说没有一定没有）。

### 3.2 什么是缓存击穿？如何解决？

**答**：

**击穿**：某个**热点 key 过期瞬间**，大量并发请求同时打到数据库。

**解决方案**：

1. **互斥锁**：缓存失效时，只允许第一个线程查 DB 回填，其他线程等待。Redis SETNX 实现：

```java
if (setnx("lock:key", 1, 10s)) {
    try { 查DB回填缓存 } finally { del("lock:key") }
} else {
    sleep(50ms); 重试读缓存
}
```

2. **逻辑过期**：value 里存"逻辑过期时间"，永不真正过期。读到过期值的线程异步去更新，其他线程继续返回旧值

3. **热点 key 永不过期 + 定时任务更新**

### 3.3 什么是缓存雪崩？如何解决？

**答**：

**雪崩**：**大量 key 同时过期** 或 **Redis 宕机**，请求全部打到 DB，把 DB 压垮。

**解决方案**：

1. **随机 TTL**：基础 TTL + 随机偏移（如基础 30 分钟 + 0-5 分钟随机）
2. **多级缓存**：本地 Caffeine + Redis 二级
3. **熔断降级**：DB 压力过大时直接返回兜底数据
4. **Redis 高可用**：哨兵 / 集群，避免单点故障
5. **业务层限流**：保证 DB 不会被打挂

**穿透/击穿/雪崩对比**：

| | 穿透 | 击穿 | 雪崩 |
|:---|:---|:---|:---|
| 现象 | 查不存在的数据 | 单个热 key 过期 | 大量 key 同时过期或 Redis 挂 |
| 范围 | 单点 | 单点 | 大面积 |
| 主要应对 | 布隆 + 缓存空值 | 互斥锁 + 逻辑过期 | 随机 TTL + 高可用 |

### 3.4 缓存和数据库的一致性如何保证？

**答**：

强一致性几乎不可能（除非用 2PC，性能差到不可用）。实际做**最终一致**。

**几种模式**：

1. **Cache-Aside（旁路缓存，最常用）**：
   - 读：先读缓存，没有再读 DB 回填
   - 写：先写 DB，再删缓存
2. **Write-Through**：写入缓存 → 缓存同步写 DB
3. **Write-Behind / Write-Back**：写入缓存 → 异步刷 DB（性能好但可能丢数据）
4. **Read-Through**：读缓存未命中时，缓存自己去查 DB 加载

互联网场景大多用 Cache-Aside。

### 3.5 先更新数据库还是先删缓存？延迟双删是什么？

**答**：

**先更新 DB 再删缓存** 比 **先删缓存再更新 DB** 更安全。前者在并发场景出错的概率小（缓存被脏 read 的窗口短）。

**两种异常场景**：

- 先删后更：A 删缓存 → B 读缓存（miss）→ B 读 DB 旧值 → B 回填缓存（旧值！）→ A 更新 DB → 缓存和 DB 不一致
- 先更后删：A 更新 DB → A 删缓存。中间窗口很短，理论上仍可能有 race，但概率小

**延迟双删（兜底）**：

```
1. 删缓存
2. 更新 DB
3. sleep N ms（覆盖读取 + 回填的时间窗口）
4. 再删一次缓存
```

第二次删保证即使被脏读回填，也会被清掉。代价是写延迟增加。

**更彻底的方案**：订阅 binlog（Canal），DB 变更触发缓存删除，跟业务代码解耦。

### 3.6 热点 key 问题如何处理？

**答**：

**问题**：单个 key 被高频访问，把单个 Redis 实例 / 单个 Slot CPU 打满。

**识别**：

- `redis-cli --hotkeys` 扫描（需开 LFU）
- 监控某 key 的 QPS / 命中率
- 业务层主动标记（如商品详情）

**解决**：

1. **本地缓存（多级缓存）**：JVM 内 Caffeine 缓存热 key，扛掉 90% 流量
2. **复制多份**：把 key 复制成 `key:1`、`key:2`、`key:3`...`key:10`，请求随机选一个。10x 分散
3. **读写分离**：从库分担读压力
4. **限流**：单 key 超过阈值降级
5. **Redis Cluster 不再用相同 hash tag**

### 3.7 大 key 问题如何排查和处理？

**答**：

**大 key 定义**：

- String value > 10 KB
- Hash / List / Set / ZSet 元素数 > 5000

**危害**：

- 序列化 / 网络传输慢
- 阻塞主线程（DEL 一个 1GB 的 key 可能阻塞秒级）
- 内存倾斜（某个槽位 / 节点内存特别大）
- 持久化时 fork 慢

**排查**：

- `redis-cli --bigkeys` 扫描
- `MEMORY USAGE key` 看占用
- `DEBUG OBJECT key` 看详细
- RDB 离线分析工具（如 rdr）

**处理**：

1. **拆分**：大 hash 拆成多个小 hash，按字段哈希到不同子 key
2. **压缩**：value 做压缩（如 Gzip）
3. **异步删除**：Redis 4.0+ 用 `UNLINK key`（后台线程删）替代 `DEL`
4. **业务层流式处理**：分批读取，不要一次性 HGETALL 全部

---

## 四、高可用

### 4.1 Redis 主从复制的原理？全量同步和增量同步的区别？

**答**：

**全量同步（初次同步 / 复制中断时间长）**：

1. Slave 发送 `PSYNC ? -1` 给 Master
2. Master 执行 `BGSAVE` 生成 RDB
3. Master 把 RDB 文件 + 期间的增量写命令发给 Slave
4. Slave 清空数据，加载 RDB
5. Slave 重放增量

**增量同步（断线重连后）**：

- Master 维护**复制积压缓冲区**（环形缓冲区，1MB 默认）
- Slave 发 `PSYNC <replid> <offset>` 带上自己同步到哪
- 如果 offset 还在缓冲区里：只补发缺失部分（增量同步）
- 如果不在：退化为全量同步

**关键参数**：

- `repl-backlog-size`：积压缓冲区大小，主从断开时间长就要调大
- `replicaof <host> <port>`：配置主从关系

### 4.2 Redis Sentinel 哨兵模式的原理？

**答**：

哨兵集群（通常 3 节点）独立运行，负责监控、故障转移、通知。

**核心功能**：

1. **监控**：每秒 PING Master/Slave
2. **故障检测**：
   - **主观下线（SDOWN）**：单个哨兵认为下线
   - **客观下线（ODOWN）**：多数哨兵都认为下线
3. **自动故障转移**：
   - 哨兵之间选 Leader（Raft 算法）
   - Leader 在 Slave 中选新 Master（优先级、复制偏移量、runid）
   - 通知其他 Slave 指向新 Master
   - 通知客户端
4. **配置中心**：客户端通过哨兵发现当前 Master 地址

**哨兵自己也是分布式的**，至少 3 个哨兵（多数派投票）。

### 4.3 Redis Cluster 集群的原理？

**答**：

Redis 官方分布式方案。**去中心化**，无哨兵需求。

**架构**：

- 至少 3 主 3 从
- 16384 个槽位（slot），分布到所有 Master
- 每个 Master 负责一部分 slot
- key 用 CRC16(key) % 16384 算出 slot，路由到对应 Master

**通信**：

- 节点间用 **Gossip 协议** 互通状态
- 客户端连任一节点，节点返回 MOVED 指令告诉客户端去哪个节点
- 智能客户端（如 Jedis Cluster）会缓存 slot → node 映射

**故障转移**：

- 节点间互相 PING，超过 cluster-node-timeout 标记 PFAIL
- 多数节点都认为下线 → FAIL
- 该 Master 的 Slave 们投票选新 Master

### 4.4 Cluster 如何做数据分片？槽位是什么？

**答**：

**槽位（Slot）**：把 key 空间分成 16384 份。每个 Master 负责其中一部分（比如 3 主集群每个负责约 5461 个 slot）。

**为什么是 16384 不是 65536**：

1. 心跳包大小考虑（16384 = 2KB bitmap）
2. 集群规模不会超过 1000 节点，16384 个 slot 够用

**计算 slot**：

```
slot = CRC16(key) mod 16384
```

**Hash Tag**：如果 key 含 `{}`，只对花括号内部计算。例如 `user:{1000}:profile` 和 `user:{1000}:orders` 会落在同一个 slot，可以用 MULTI 跨 key 事务。

**重分片**：动态添加节点时，slot 可以在节点间迁移。客户端访问迁移中的 key 时收到 ASK 重定向。

### 4.5 Cluster 脑裂问题是什么？如何避免？

**答**：

**脑裂**：网络分区时，集群被切成两部分。Master 在 A 分区，Slave 在 B 分区。B 分区的 Slave 被选成新 Master。此时如果客户端 也在 A 分区，仍连旧 Master 写数据，等网络恢复后，旧 Master 的数据丢失（被新 Master 同步覆盖）。

**避免**：

1. `cluster-require-full-coverage no`：允许部分 slot 不可用，避免全集群拒绝服务
2. `min-replicas-to-write 1`（旧名 min-slaves-to-write）：Master 至少要有 1 个 Slave 才接受写，否则拒绝
3. `min-replicas-max-lag 10`：Slave 同步延迟超过 10s 视为不可用

合理配置能让脑裂中的"少数派 Master"自动拒绝写入。

### 4.6 Redis 集群下如何保证事务？

**答**：

Redis 单机 MULTI/EXEC 在 Cluster 模式下有限制：**事务里的所有 key 必须在同一个 slot**。

**解决**：

1. **Hash Tag**：把相关 key 用 `{...}` 强制路由到同一 slot
2. **Lua 脚本**：所有操作在一个节点上原子执行（但同样要求 key 在同一 slot）
3. **避免跨 slot 事务**：业务设计上把要事务的数据放一起

**严格分布式事务**需要外部协调（如 TCC、Saga 模式）。

---

## 五、分布式

### 5.1 如何用 Redis 实现分布式锁？

**答**：

**基础版**：

```
SET lock_key unique_value NX EX 10
-- NX: 不存在才设
-- EX: 10 秒过期
```

释放锁要**判断 value 再删**，避免删错别人的锁：

```lua
-- 用 Lua 脚本保证原子
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end
```

**问题**：

1. 业务超过 10 秒，锁自动释放，但业务还没完，别人拿了锁 → 用 **看门狗**
2. 主从切换时锁可能丢失 → 用 **RedLock**

### 5.2 Redisson 的看门狗机制是什么？

**答**：

Redisson 是 Redis 的 Java 客户端，提供高级分布式锁。

**问题**：业务超过锁过期时间怎么办？

**看门狗（Watchdog）**：

- 加锁后默认 30 秒 TTL
- Redisson 启动一个后台定时任务，每 10 秒（TTL/3）检查一次
- 如果业务还在执行（锁还在持有），就续期到 30 秒
- 业务结束 / 进程崩溃 → 续期停止 → 锁自然过期

**用法**：

```java
RLock lock = redisson.getLock("myLock");
lock.lock();         // 启动看门狗
try {
    业务逻辑
} finally {
    lock.unlock();   // 停止看门狗
}
```

**注意**：

- 显式指定 leaseTime 的 `lock(30, TimeUnit.SECONDS)` **不会启动看门狗**
- 进程崩溃后看门狗停止，锁会自然过期，避免死锁

### 5.3 RedLock 算法是什么？有什么争议？

**答**：

**问题**：单点 Redis 锁在主从切换时可能丢失。Master 写入锁后还没同步到 Slave 就挂了，新 Master 没这个锁，另一个客户端拿到了 → 锁失效。

**RedLock（Antirez 提出）**：

1. 客户端向 **N 个独立的 Master**（互相不主从复制，通常 5 个）依次申请锁
2. 全部用相同 key + value + TTL
3. **多数（N/2+1）个**节点都加锁成功 + 总耗时 < TTL，认为加锁成功
4. 任一失败要回滚（释放已经拿到的锁）

**争议**：

- Martin Kleppmann 写文章反对：时钟漂移、GC 暂停、网络分区都可能让 RedLock 失效
- antirez 回应：RedLock 在合理假设下是正确的

**结论**：

- 一般场景 Redisson 看门狗就够了
- 极度严苛的场景用 Zookeeper / etcd（强一致 KV）
- RedLock 不是银弹，理解清楚再用

### 5.4 Redis 实现消息队列有哪些方案？各自的优缺点？

**答**：

**方案 1：List（LPUSH + BRPOP）**

```
LPUSH queue msg
BRPOP queue 0
```

- 优点：简单
- 缺点：不持久化（消费后删）、不支持广播、消费一次只能一个消费者

**方案 2：Pub/Sub**

```
SUBSCRIBE channel
PUBLISH channel msg
```

- 优点：一对多广播
- 缺点：消息不持久化（消费者离线就丢）、不支持 ACK

**方案 3：ZSet（延迟队列）**

```
ZADD delay_queue <execute_at_ms> msg
ZRANGEBYSCORE delay_queue 0 <now>  # 取到执行时间到了的
```

- 优点：能做延迟队列
- 缺点：消费要轮询，性能差

**方案 4：Stream（5.0+ 推荐）**

```
XADD stream * key val
XGROUP CREATE stream group $
XREADGROUP GROUP group consumer COUNT 10 BLOCK 0 STREAMS stream >
XACK stream group msg_id
```

- 优点：持久化、消费者组、ACK、Pending List 重投递
- 缺点：用得少，社区文档不如专业 MQ 丰富

**结论**：

- 简单场景：Stream
- 高吞吐 + 严格保证：Kafka / RocketMQ

### 5.5 Redis 的 Lua 脚本有什么用？原子性如何保证？

**答**：

**用途**：

1. 把多个命令打包成原子操作（如 SETNX + EXPIRE 合一）
2. 减少网络往返（一次 RTT 执行多命令）
3. 复杂业务逻辑（如限流、分布式锁）

**原子性保证**：Redis 单线程执行命令，Lua 脚本执行期间**不会被其他命令打断**。

**用法**：

```bash
EVAL "return redis.call('GET', KEYS[1])" 1 mykey
```

**注意**：

- 脚本里不要有死循环 / 慢操作（会阻塞整个 Redis）
- 脚本不能调外部网络
- 集群模式下脚本访问的所有 key 必须在同一 slot

### 5.6 Redis 实现限流的方案有哪些？

**答**：

**方案 1：固定窗口**

```
INCR key      -- 计数 +1
EXPIRE key 60 -- 设过期
if count > 100 then 拒绝
```

简单但有"窗口边界突刺"问题（59 秒来 100 个 + 1 秒后又 100 个，1 秒内放过 200 个）。

**方案 2：滑动窗口（ZSet）**

```
当前时间戳作 score，唯一 ID 作 member
ZREMRANGEBYSCORE key 0 (now-60s)   -- 清理 60 秒前的
ZADD key now uniq_id
ZCARD key                          -- 当前窗口内请求数
```

更精确，但 ZSet 操作开销大。

**方案 3：令牌桶（Lua 脚本实现）**

```
local tokens = redis.call("get", KEYS[1])
if tokens > 0 then
    redis.call("decr", KEYS[1])
    return 1   -- 允许
else
    return 0   -- 拒绝
end
```

配合定时任务每秒补 N 个 token。

**方案 4：Redis-Cell 模块（GCRA 算法）**

```
CL.THROTTLE key 15 30 60 1
-- 容量 15，每 60 秒 30 个，本次请求 1
```

性能好且精确，但需要安装模块。

---

## 六、进阶

### 6.1 Redis 的事务和数据库事务的区别？

**答**：

Redis 的 MULTI/EXEC 只是"把命令批量发送 + 顺序执行"，**没有真正的事务语义**。

| | DB 事务 | Redis 事务 |
|:---|:---|:---|
| 原子性 | 全成功或全回滚 | 不保证（中间错误后面继续执行）|
| 隔离性 | 看隔离级别 | 单线程天然隔离 |
| 持久性 | redo log 保证 | 看持久化配置 |
| 回滚 | 支持 | **不支持**（命令错了不会回滚已执行的）|

只在两种情况整体不执行：

1. EXEC 前命令有语法错误（编译期错误）
2. WATCH 监视的 key 被改了（乐观锁机制）

**实际中**：Redis 事务用得少，更多用 Lua 脚本保证原子性。

### 6.2 Redis Pipeline 是什么？适合什么场景？

**答**：

**Pipeline**：客户端把多个命令一次性发给服务端，服务端依次执行后一次性返回所有结果。**节省网络 RTT**。

```
单条：N 条命令 = N 次 RTT
Pipeline：N 条命令 = 1 次 RTT（命令打包发送）
```

**和 MULTI 的区别**：

| | Pipeline | MULTI/EXEC |
|:---|:---|:---|
| 网络 | 批量发送 | 批量发送 |
| 原子性 | 无（命令之间可能被其他客户端命令插入）| 有（中间不被打断）|
| 性能 | 最快 | 略慢 |
| 错误处理 | 单条出错继续 | 同上 |

**适用场景**：批量写入、批量查询。

**注意**：

- 单次 Pipeline 不要太大（< 10000 条），否则占用服务端内存
- Pipeline 期间客户端阻塞等结果，期间不能做其他事

### 6.3 Redis 的发布订阅和 Stream 的区别？

**答**：

| | Pub/Sub | Stream（5.0+）|
|:---|:---|:---|
| 持久化 | 否 | 是 |
| 消费者离线 | 消息丢失 | 离线消息保留，上线后能继续消费 |
| 消费者组 | 不支持 | 支持（多个消费者分摊消息） |
| ACK 机制 | 否 | 是（XACK） |
| 消息回溯 | 不支持 | 任意位置消费历史 |
| 性能 | 极快 | 略慢 |
| 适用场景 | 实时通知、不在乎丢消息 | 轻量级 MQ |

### 6.4 Redis 布隆过滤器的原理和使用场景？

**答**：

**原理**：用 K 个哈希函数把每个元素映射到 bit 数组的 K 个位置，置为 1。查询时也用同样的 K 个函数，**所有位置都是 1 才认为可能存在**。

**特点**：

- 空间极省（亿级数据用百 MB）
- 查询 O(K)
- **存在假阳性**（说有但实际没）
- **不存在假阴性**（说没有一定没有）
- **不支持删除**（删一个元素可能影响其他元素的判断）

**Redis 实现**：

1. 自己用 `SETBIT`/`GETBIT` 实现（要算哈希、维护参数）
2. **RedisBloom 模块**：`BF.ADD` / `BF.EXISTS`

```bash
BF.RESERVE myfilter 0.01 1000000   -- 误判率 1%，100 万容量
BF.ADD myfilter item1
BF.EXISTS myfilter item1
```

**使用场景**：

- 缓存穿透防护（在缓存前过滤无效 key）
- 防止重复消费（消息 ID 去重）
- URL 去重（爬虫）
- 邮箱黑名单

### 6.5 Redis 的内存碎片是什么？如何处理？

**答**：

**碎片来源**：

- 频繁分配 + 释放小对象，allocator（jemalloc）无法把零散空间合并
- value 大小经常变化（如 hash 不断增删字段）

**衡量**：`INFO memory` 看：

- `used_memory`：实际占用
- `used_memory_rss`：操作系统视角的占用
- `mem_fragmentation_ratio = rss / used`：碎片率

经验值：

- < 1：使用了 swap，性能差
- 1-1.5：正常
- > 1.5：碎片严重

**处理**：

1. **重启 Redis**：最彻底，但要切主从
2. **自动碎片整理**：4.0+ 支持，`activedefrag yes`。后台扫描重新分配大块内存。CPU 开销中等

参数：

- `active-defrag-ignore-bytes 100mb`：碎片少于 100MB 不整理
- `active-defrag-threshold-lower 10`：碎片率 < 10% 不整理
- `active-defrag-cycle-min 1`：最低 CPU 占用 1%
