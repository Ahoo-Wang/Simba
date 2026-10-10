---
title: 后端
description: JDBC、Redis 和 Zookeeper 后端如何存储租约、各自需要什么，以及各自如何失效。
---

# 后端

所有后端都实现同一份契约（见 [正确性](/zh/guide/correctness)），并由共享的 [TCK](/zh/contributing/#后端-tck) 验证。
它们的区别在于租约存放在哪里、由哪个时钟决定，以及后端自身故障时会发生什么。

## JDBC（MySQL）

**存储。** `simba_mutex` 中每个 mutex 一行。用
[`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/init-script/init-simba-mysql.sql)
建表一次即可；行在首次使用时插入。

```sql
create table if not exists simba_mutex
(
    mutex         varchar(66)     not null primary key comment 'mutex name',
    acquired_at   bigint unsigned not null,
    ttl_at        bigint unsigned not null,
    transition_at bigint unsigned not null,
    owner_id      varchar(128)    not null,
    version       int unsigned    not null,
    fencing_token bigint unsigned not null default 0
);
```

**获取与续期** 是同一条条件 `UPDATE`，使用数据库时钟求值：当租约已结束（`transition_at < now`），或调用者已经持有且租约未结束时成功。
随后在同一事务中读回该行以及数据库时间（`current_at`），因此租约判断从不比较不同机器的挂钟。

**Fencing token** 来自 `fencing_token` 列，只在新任期开始时于该 `UPDATE` 中递增。默认开启（`simba.jdbc.fencing=true`）。
3.3 之前创建的表需要执行
[`upgrade-simba-mysql-fencing-token.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/init-script/upgrade-simba-mysql-fencing-token.sql)，
或设置 `simba.jdbc.fencing=false`。

**运维要点**

- SQL 是 MySQL 专用的（`unix_timestamp(current_timestamp(3))`、`if(...)`、`SET` 从左到右求值）。
- 竞争者靠轮询：正常 `stop()` 后，下一个持有者在其下一次计划的尝试时接管，而不是立即接管。
- Starter 把语句超时设为 `simba.jdbc.ttl`，因此挂起的查询阻塞竞争的时间不会超过一个租约。
- 数据库不可用：持有者在租约有效期内保持所有权，之后在本地撤销。数据库恢复前没有任何持有者。

## Redis

**存储。** 每个 mutex 三个键，共享 `{mutex}` hash tag，因此位于同一个集群 slot：

| 键 | 内容 | 过期 |
|---|---|---|
| `simba:{mutex}` | 持有者的 contender id；同时是 pub/sub 频道 | `ttl + transition` |
| `simba:{mutex}:fence` | Fencing 计数器 | 永不过期 |
| `simba:{mutex}:token` | 当前任期的 fencing token | 随租约过期 |

**获取** 是 Lua 脚本中的 `SET NX PX`，成功时递增计数器并发布 `acquired@@{contenderId}`。**续期** 是仅由持有者执行的 `SET XX PX`。
**释放** 删除租约并发布 `released@@{contenderId}`，使所有已订阅的竞争者立即参与竞争。每个节点根据键的 `PTTL` 和本地时钟推算 `transitionAt`。

**运维要点**

- **持久化对 fencing 很重要。** 只有当计数器被持久化（AOF 配合 `appendfsync always` 或等效配置）时，它才会在 Redis 重启后继续递增。
  没有持久化时，重启会重置计数器，token 可能回退。
- 复制是异步的：故障转移到一个尚未同步最新 `SET` 的副本时，可能授予第二个租约。如果这很重要，请使用 fencing token，或使用同步复制的后端。
- 混合版本：4.x 节点要求同一 mutex 的所有节点都运行 Simba 3.2 或更高版本。

## Zookeeper

**存储。** 位于 `/simba/{mutex}` 的 Curator `LeaderLatch`：每个竞争者创建一个临时顺序节点，序号最小者成为 leader。
没有 TTL：`ttl` 和 `transition` 不适用，leader 关闭 latch 或会话过期时领导权结束。需要提供一个已启动的 `CuratorFramework` bean；
starter 不会创建它。

**Fencing token** 是获胜 latch 节点的 `czxid`。ZooKeeper 事务 id 在整个集群内单调递增，因此每个新 leader 都会得到更大的 token。
（节点序号不可用：父节点是容器节点，为空时会被 ZooKeeper 删除，序号随之重新开始。）

**运维要点**

- 崩溃后的故障转移时间取决于 `CuratorFramework` 上配置的会话超时。
- 连接中断（`SUSPENDED`）时 Curator 会撤销领导权；节点重连后重新竞争，并获得新的 token。
- 在 Zookeeper 上 Simba 只观察自己的领导权：非 leader 不知道谁是 leader，它们的 [`simba` 端点](/zh/guide/observability#actuator-端点)
  不显示当前持有者。由于竞争由 Curator 执行，不会上报竞争指标。

## 在 Spring Boot 中选择后端

当后端模块位于 classpath、`simba.enabled` 与 `simba.<backend>.enabled` 为 `true`（默认）并且其基础设施 bean 存在时，该后端被激活。
如果激活了多个后端模块，请设置 `simba.backend=jdbc|redis|zookeeper`；否则启动失败，而不是依赖自动配置的顺序。
