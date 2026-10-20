---
title: 从 4.x 迁移到 5.0
description: 将 Simba 4.x 的 API、属性、schema 和 Redis 键对应到 Simba 5.0，并通过短暂停止所有节点完成切换。
---

# 从 4.x 迁移到 5.0

Simba 5 是一次重写（[ADR 0010](https://github.com/Ahoo-Wang/Simba/blob/main/docs/adr/0010-simba-5-target-architecture.md)）：
每个进程一个会话，而不是每个 mutex 一个租约；用 `Term` 对象取代 owner 状态；三种后端共用一个引擎。它在源码、二进制和线协议上都
**与 4.x 不兼容**：

- JDBC 表、Redis 键和 Zookeeper 路径都不同；
- 配置属性和 Spring bean 都不同；
- 需要 Java 21，starter 需要 Spring Boot 4.1 或更高版本；
- Redis 后端被拆分：单独的 `simba-redis` 只定义了 `RedisScripting` 端口，无法运行。请依赖 `simba-spring-redis`
  （基于 Lettuce 的 Spring Data Redis，它会带上 `simba-redis`），或者为其他客户端自行实现 `RedisScripting`。

## 为什么必须停止所有节点 {#why-every-node-must-stop}

互斥只在查看同一份租约的节点之间成立。4.x 节点和 5.0 节点把租约存放在不同的地方，因此双方都认为该名称空闲，会同时成为 leader。
所以你无法逐个节点滚动这次升级：**先停止竞争同一名称的所有节点，再以 5.0 启动它们。** 如果哪怕短暂的双重运行也会造成危害，
请确保第一个 5.0 节点启动前没有任何 4.x 节点在运行。

## 步骤 {#steps}

1. 按照下面的 [API 对照](#api-mapping) 更新代码，按照 [属性对照](#property-mapping) 更新配置。
2. 准备后端：
   - **JDBC：** 使用
     [`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-mysql.sql)
     或
     [`init-simba-postgresql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-postgresql.sql)
     创建 5.x 的表。4.x 的表 `simba_member(group_name, member_id, expire_at)` 与 5.x 的表 `simba_member(group_name, session_id)`
     同名但列不同，而脚本使用的是 `create table if not exists`：**请先删除或重命名 4.x 的 `simba_member`**，或者使用单独的数据库或 schema。
   - **Redis、Zookeeper：** 无需创建任何东西。5.x 的键和节点与 4.x 的不重叠，唯一的例外是 4.x 的 latch 节点同样位于 `/simba` 下；
     名为 `leases` 或 `groups` 的 4.x mutex 会与 5.x 的子树冲突，因此请删除旧节点或将 `simba.zookeeper.root` 设置为其他路径。
3. 停止所有 4.x 节点。
4. 启动 5.0 节点。
5. 确定不再需要回滚后，删除 4.x 的存储：表 `simba_mutex`（以及 4.x 的 `simba_member`），键 `simba:{mutex}`、
   `simba:{mutex}:fence`、`simba:{mutex}:token`、`simba:{group}:members`，Zookeeper 节点 `/simba/{mutex}` 和 `/simba-members`。

Fencing token 会重新开始：5.x 的 token 来自新的存储，从 `1` 开始（Zookeeper：新节点的 `czxid`）。记录过 4.x token 的资源会拒绝最初的
5.x 写入；请在切换期间重置它记录的最大 token，或者让 5.x 的 token 从更大的值开始（JDBC 下，可在首次启动前插入 `token` 更大、
`session_id` 为空的 `simba_lease` 行）。

## API 对照 {#api-mapping}

| 4.x | 5.0 |
|---|---|
| `MutexContendServiceFactory` + `MutexContender` + `start()` | `simba.election(name).start(listener)` |
| `AbstractMutexContender.onAcquired(mutexState)` / `onReleased(mutexState)` | `LeadershipListener.onElected(term)` / `onRevoked(term, reason)` |
| `MutexContendService.stop()` | `election.stop()`（一个 `CompletionStage`） |
| `relinquish(cooldown)` | `election.stepDown(cooldown)` |
| `MutexOwner` / `MutexState` | `Term`（本节点的任期）；不再提供其他节点所有权的视图 |
| 服务、locker、调度器、上下文上的 `fencingToken`、`leaseRemaining`、`ensureLease()` | 工作所收到的 `Term` 上的 `term.token`、`term.remaining()`、`term.ensureValid()` |
| `isOwner`、`hasOwner()` | `term.isValid`；本节点的选举用 `simba.snapshot()` |
| `contenderId`（重启后复用） | `SessionId`（每个实例唯一，永不复用） |
| `SimbaLocker(mutex, factory).acquire(timeout)` | `simba.lock(name).acquire(timeout)`，返回 `LockTerm` |
| `SimbaScheduler` / `AbstractScheduler` + `ScheduleConfig.delay/rate` | `election.schedule(Schedule.fixedDelay/fixedRate) { term -> }` |
| `ScheduleContext` | 传给工作的 `Term` |
| `SimbaPartitionedScheduler` + `MemberRegistry` | `simba.group(name, partitions).schedule { term, partition -> }` |
| `PartitionContext` | `(term, partition)` |
| 使用 `ScheduleContext` 的 `@SimbaScheduled(mutex = ..., worker = ...)` | 使用 `Term` 的 `@SimbaScheduled(name = ...)` |
| — | 使用 `(Term, Int)` 的 `@SimbaPartitioned(group, partitions, ...)` |
| `ContendObserver` | 接收 `SimbaEvent` 的 `SimbaEventListener` |
| `MutexLeaseStore`、`MutexOwnerRepository`、`MemberRegistry`（SPI） | `LeaseBackend` |
| `JdbcMutexContendServiceFactory` | `JdbcLeaseBackend.mysql(dataSource)` / `postgres(dataSource)` |
| `SpringRedisMutexContendServiceFactory` | `RedisLeaseBackend(SpringRedisScripting(connectionFactory))` |
| `ZookeeperMutexContendServiceFactory`（Curator `LeaderLatch`） | `ZookeeperLeaseBackend(connectString)`（临时节点） |
| `MutexContendServiceSpec`、`LockSpec`、`MemberRegistrySpec`（`simba-test`） | `LeaseBackendSpec`（`simba-tck`） |
| `FencingSpec`（`simba-test`） | `FencingSpec`（`simba-tck`） |
| — | `simba-testing`：`InMemoryLeaseBackend`、`VirtualScheduler`、`Simulation` |
| `Simba`（4.x 的常量对象 `SIMBA`、`SIMBA_PREFIX`） | `Simba` 现在是客户端类（`Simba.builder()`）；这些常量已删除 |
| `MutexRetriever`、`MutexRetrievalService`、`AbstractMutexRetrievalService`（观察者） | 没有对应物：节点只能看到自己的任期。用 `simba.snapshot()` 查询本节点的选举，或读取后端存储（见[后端](/zh/guide/backends)）查看谁持有租约 |
| `Locker`（`SimbaLocker` 的接口） | `DistributedLock`（`simba.lock(name)`），其 `acquire` 返回 `LockTerm` |
| `LeaseConfig`（`ttl`、`transition`） | `SimbaConfig`（`leaseDuration`、`renewInterval`、`drift`、`margin`），每个客户端一个 |
| `ContenderIdGenerator` | 已删除：后端为每个会话分配一个 `SessionId` |
| `MicrometerContendObserver` | `MicrometerSimbaEventListener` |
| `SimbaServiceTracker`（Actuator） | 已删除：端点读取 `simba.snapshot()` |
| `ScheduledWork` / `PartitionedWork` | `LeaderWork`（`run(term)`）/ `PartitionWork`（`run(term, partition)`） |
| `me.ahoo.simba.core.LeaseExpiredException` | `me.ahoo.simba.TermExpiredException`，由 `Term.ensureValid` 抛出 |

行为上的变化：

- **每个任期都是一个新对象。** 回调或工作块只会看到它自己的任期，因此不会意外读到后一个任期的 token。
- **每个节点一个会话。** 一次续约（keep-alive）续期所有租约和成员关系；一次续约失败会影响全部。如需隔离，请创建多个 `Simba` 客户端。
- **锁不绑定线程。** `LockTerm` 可以从任意线程关闭；提前失去的锁不会被重新获取。
- **不再推送其他节点的所有权。** 4.x 会把其他节点的获取通知给每个竞争者；5.x 只报告本节点的任期。
- **Zookeeper 租约与其他后端一样有界限：** `remaining()` 在三种后端上含义相同，`LeaderLatch` 的 FIFO 公平性不复存在。
- **Leader 任务运行在虚拟线程上**，每个任期一个；不再有每个调度器一个的线程池。

### 已删除的内部类型 {#removed-internals}

以下 4.x 公共类型属于实现细节，5.0 没有可供编码的替代品。请配置一个后端（`JdbcLeaseBackend`、`RedisLeaseBackend`、
`ZookeeperLeaseBackend`）并改用 `Simba`：

| 模块 | 4.x 类型 | 5.0 |
|---|---|---|
| `simba-core` | `AbstractMutexContendService`、`LeaseContendService`、`ContendPeriod`、`ContendExecutors`、`ContendOutcome` | 引擎（内部）；执行器在 `Simba.builder()` 上设置，结果以 `SimbaEvent` 发布 |
| `simba-core` | `UUIDContenderIdGenerator`、`HostContenderIdGenerator` | 无：后端为每个会话分配一个 `SessionId` |
| `simba-jdbc` | `JdbcMutexContendService`、`JdbcMutexOwnerRepository`、`JdbcMemberRegistry`、`NotFoundMutexOwnerException` | `JdbcLeaseBackend` |
| `simba-spring-redis` | `SpringRedisMutexContendService`、`SpringRedisMemberRegistry`、`OwnerEvent` | 基于 `SpringRedisScripting` 的 `RedisLeaseBackend`；由释放提示取代 owner 事件 |
| `simba-zookeeper` | `ZookeeperMutexContendService`、`ZookeeperMemberRegistry` | `ZookeeperLeaseBackend` |
| `simba-spring-boot-starter` | `SimbaSpringRedisAutoConfiguration` | `SimbaRedisAutoConfiguration` |

## 属性对照 {#property-mapping}

| 4.x | 5.0 |
|---|---|
| `simba.enabled`、`simba.backend`、`simba.<backend>.enabled` | 不变 |
| `simba.jdbc.ttl`、`simba.redis.ttl` + `transition` | `simba.session.lease-duration` 和 `simba.session.renew-interval`，适用于所有后端 |
| `simba.jdbc.initial-delay` | 已移除 |
| `simba.jdbc.fencing` | 已移除：token 始终开启 |
| — | `simba.session.drift`、`simba.session.margin` |
| — | `simba.jdbc.dialect`、`simba.jdbc.query-timeout` |
| — | `simba.redis.wait-replicas`、`simba.redis.wait-timeout`、`simba.redis.route-by-slot` |
| `CuratorFramework` bean（Zookeeper） | `simba.zookeeper.connect-string`、`root`、`connection-timeout` |
| `simba.scheduling.enabled`、`simba.metrics.enabled` | 不变 |

作为起点，`lease-duration ≈ ttl + transition` 可以保持 4.x 的故障转移时间。

## 存储对照 {#storage-mapping}

| | 4.x | 5.0 |
|---|---|---|
| JDBC | `simba_mutex`（每个 mutex 一行）、`simba_member(group_name, member_id, expire_at)` | `simba_session`、`simba_lease`、`simba_member(group_name, session_id)` |
| Redis | `simba:{mutex}`、`simba:{mutex}:fence`、`simba:{mutex}:token`、`simba:{group}:members`、频道 `simba:{mutex}` | `simba:lease:{name}`、`simba:session:{sid}`（+ `:leases`、`:groups`）、`simba:group:{group}` |
| Zookeeper | 位于 `/simba/{mutex}` 的 `LeaderLatch`，成员 `/simba-members/{group}/{memberId}` | `/simba/leases/{name}`、`/simba/groups/{group}/{sessionId}` |

分区租约保留 `{group}.{partition}` 名称和相同的 rendezvous 权重，但成员现在是会话 id，因此分区到节点的分配会发生变化。

## 指标与端点对照 {#metrics-and-endpoint-mapping}

| 4.x | 5.0 |
|---|---|
| `simba.mutex.owner{mutex}` | `simba.leadership{name}` |
| `simba.mutex.ownership.changes{mutex,change}` | `simba.revocations{reason,kind}`（所有结束的任期，包括 `stopped` 和 `stepped_down`）；锁：`simba.locks.held` |
| `simba.mutex.contend{mutex,operation,outcome}` | `simba.session.renew`（每个节点一次续约） |
| `simba.mutex.lease.expired{mutex}` | `simba.revocations{reason="deadline_passed"}` |
| `simba.scheduler.work{mutex,outcome}` | `simba.work{name,outcome}` |
| — | `simba.session.state{state}` |
| 按 mutex 和竞争者划分的 `/actuator/simba` | `/actuator/simba`：会话及选举，来自 `snapshot()` |
| `simba` 健康检查：陈旧的竞争者 | `simba` 健康检查：会话非 live 状态的时间超过租约 |

请相应地更新仪表盘和 [告警规则](/zh/guide/observability#alert-rules)。
