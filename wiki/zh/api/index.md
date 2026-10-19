---
title: API 参考
description: Simba 5 的公共类型及其契约——Simba、Election、Term、DistributedLock、Schedule、PartitionGroup、SimbaEvent、SimbaSnapshot、LeaseBackend SPI 以及 Spring 注解。
---

# API 参考

公共 API 位于 `me.ahoo.simba`（`simba-core`）及其子包 `partition`、`event` 和 `spi`。时间一律使用 `java.time.Duration`。
`me.ahoo.simba.engine` 中的类型以及标注了 `@InternalSimbaApi` 的成员需要显式 opt-in，不属于公共 API。

## 客户端 — `me.ahoo.simba` {#client-me-ahoo-simba}

### `Simba` {#simba}

每个进程一个客户端：一个后端会话，所有选举、锁和组都依附于它。实现 `AutoCloseable`。

| 成员 | 说明 |
|---|---|
| `Simba.builder()` | 构建器；参阅 [不使用 Spring](/zh/guide/configuration#without-spring) |
| `config: SimbaConfig` | 会话的时间参数 |
| `election(name): Election` | `name` 的选举；同名的句柄共享同一个选举 |
| `lock(name): DistributedLock` | `name` 上的锁；同名的锁和选举共享同一个租约 |
| `group(name, partitions): PartitionGroup` | 名为 `name`、包含 `partitions` 个分区的分区组 |
| `snapshot(): SimbaSnapshot` | 会话及所有已启动的选举，不调用后端 |
| `closeAsync(): CompletionStage<Void>` | 撤销所有任期并关闭会话；在最后一个回调运行完后完成 |
| `close()` | 调用 `closeAsync()` 并等待，但在回调或 leader 任务内部调用时不等待 |

名称遵循租约名称规则：1–128 个 `[A-Za-z0-9._:-]` 字符；其他任何名称都会抛出 `IllegalArgumentException`。`close()` 之后，
所有启动某种操作的方法都会抛出 `IllegalStateException`。

### `SimbaConfig` {#simbaconfig}

`SimbaConfig.builder()`，可设置 `leaseDuration`（15s）、`renewInterval`（`leaseDuration / 3`）、`drift`（0.01）、`margin`（50ms）、
`retryJitter`（`renewInterval / 2`）、`minBackoff`（100ms）；未设置的值保持默认，派生值随已设置的值计算。`build()` 校验
`renewInterval × 2 ≤ leaseDuration` 以及 `leaseDuration × (1 − drift) − margin > renewInterval`；`toBuilder()` 派生变体。没有公开构造器：
`SimbaConfig`、事件和快照都是按值比较的普通类（没有 `copy` 和解构），以后的版本可以兼容地增加属性。参阅 [时间参数](/zh/guide/correctness#timing)。

## 选举 — `me.ahoo.simba` {#elections-me-ahoo-simba}

### `Election` {#election}

| 成员 | 说明 |
|---|---|
| `name: LeaseName` | 租约名称 |
| `start(listener)` | 开始竞争；已启动时抛出 `IllegalStateException` |
| `stop(): CompletionStage<Void>` | 停止竞争；在最后一个 `onRevoked` 运行完后完成（未启动时立即完成） |
| `stepDown(cooldown): CompletionStage<Void>` | 放弃当前任期，并在 `cooldown` 期间不参与竞争；保持已启动状态 |
| `runWhileLeader(executor?, work): LeaderTask` | 启动选举，并在每个任期内运行一次 `work` |
| `schedule(schedule, work): LeaderTask` | 启动选举，并在担任 leader 期间按 `schedule` 运行 `work` |

契约：每个 `onElected(term)` 之后都恰好跟随一个 `onRevoked(term, reason)`；同一选举的回调一次只运行一个，并按顺序运行；
除了通过 `stop()` 和 `stepDown()` 的 future 之外，引擎从不等待回调；`stop()` 完成之后不再有回调。

### `LeadershipListener` {#leadershiplistener}

```kotlin
interface LeadershipListener {
    fun onElected(term: Term)
    fun onRevoked(term: Term, reason: RevokeReason)
}
```

### `LeaderWork` 与 `LeaderTask` {#leaderwork-and-leadertask}

`fun interface LeaderWork { fun run(term: Term) }` 可以抛出任何异常；失败（包括 `Error`）会成为 `SimbaEvent.WorkCompleted`，从不影响选举，
唯一的例外是 `VirtualMachineError`（内存耗尽、栈溢出）：它结束该任期的工作，选举让出一个租期，错误会重新抛给工作线程。
`LeaderTask`（`AutoCloseable`）提供 `election`、`stop(): CompletionStage<Void>`（在工作退出或超时且租约已释放后完成）以及 `close()`。
参阅 [Leader 任务](/zh/guide/leader-work)。

### `Schedule` {#schedule}

`Schedule.fixedDelay(delay, initialDelay = 0)`、`Schedule.fixedRate(period, initialDelay = 0)`、
`withInitialDelay(initialDelay)`；`mode`、`period`、`initialDelay`。周期必须为正。超时的 fixed-rate 运行会跳过错过的启动。

## 任期 — `me.ahoo.simba` {#terms-me-ahoo-simba}

### `Term` {#term}

| 成员 | 说明 |
|---|---|
| `name: LeaseName` | 租约名称 |
| `token: Long` | Fencing token：`> 0`，任期内不变，同一名称的各任期之间严格递增 |
| `isValid: Boolean` | 未被撤销，且会话的本地截止时刻尚未过去 |
| `remaining(): Duration` | 距本地截止时刻的剩余时间；任期结束后为 `ZERO` |
| `ensureValid(minRemaining)`、`ensureValid()` | 除非有效期超过 `minRemaining`（无参数时为 `ZERO`），否则抛出 `TermExpiredException` |
| `revoked: CompletionStage<RevokeReason>` | 在任期被撤销时完成，且恰好完成一次 |

### `RevokeReason` {#revokereason}

`STOPPED`、`STEPPED_DOWN`、`SESSION_EXPIRED`、`DEADLINE_PASSED`、`TAKEN_OVER`；参阅
[租约、任期与 token](/zh/guide/correctness#leases-terms-and-tokens)。

## 锁 — `me.ahoo.simba` {#locks-me-ahoo-simba}

| 类型 | 成员 |
|---|---|
| `DistributedLock` | `name`；`acquire(timeout): LockTerm`（抛出 `LockTimeoutException`；声明 `InterruptedException`）；`tryAcquire(): LockTerm?` |
| `LockTerm : Term, AutoCloseable` | `releaseAsync(): CompletionStage<Void>`；`close()`（幂等） |
| `LockTimeoutException` | `name`、`timeout` |

参阅 [锁](/zh/guide/locks)。

## 分区 — `me.ahoo.simba.partition` {#partitions-me-ahoo-simba-partition}

| 类型 | 成员 |
|---|---|
| `PartitionGroup` | `name`（`String`：它命名的是后端的成员组而不是租约，但遵循租约名称规则）、`partitions`；`schedule(schedule, work): GroupTask`；`runWhileLeader(work): GroupTask` |
| `PartitionWork` | `fun run(term: Term, partition: Int)` |
| `GroupTask : AutoCloseable` | `group`；`partitions(): Set<Int>`（本节点在其最新视图中的分区）；`stop()`；`close()` |
| `PartitionAssignment` | `ownerOf(partition, members)`、`partitionsOf(member, partitions, members)`、`weight(partition, member)`：rendezvous 哈希（FNV-1a 64 与 SplitMix64 混合），跨版本保持一致 |

## 事件与快照 — `me.ahoo.simba.event` {#events-and-snapshot-me-ahoo-simba-event}

| 类型 | 成员 |
|---|---|
| `SimbaEventListener` | `fun onEvent(event: SimbaEvent)`；在引擎循环之外按顺序调用；异常会被记录并忽略 |
| `SimbaEvent` | `SessionOpened`、`SessionRenewed`、`SessionSuspended`、`SessionExpired`（`reason` 为 `DEADLINE_PASSED` 或 `SESSION_EXPIRED`）、`SessionLeaseShortened`、`SessionClosed`、`Elected`、`Revoked`、`AcquireFailed`、`WorkCompleted`（后四个带 `kind`） |
| `ElectionKind` | `ELECTION`、`LOCK`、`GROUP`：选举由哪个 API 启动（见 `Elected`、`Revoked` 和 `ElectionSnapshot`） |
| `WorkOutcome` | `COMPLETED`、`FAILED`、`CANCELLED` |
| `SimbaSnapshot` | `session: SessionSnapshot`、`elections: List<ElectionSnapshot>`、`leaseDuration`、`healthy` |
| `SessionSnapshot` | `id`、`status`（`OPENING`、`LIVE`、`SUSPENDED`、`CLOSED`）、`validFor`、`notLiveFor` |
| `ElectionSnapshot` | `name`、`status`（`CANDIDATE`、`LEADER`、`COOLDOWN`、`HANDOVER`）、`token`、`remaining`、`kind` |

参阅 [可观测性](/zh/guide/observability)。

## 后端 SPI — `me.ahoo.simba.spi` {#backend-spi-me-ahoo-simba-spi}

```kotlin
interface LeaseBackend : AutoCloseable {
    val capabilities: Capabilities                    // releaseHints, membershipHints, maxBatch
    fun openSession(lease: Duration, metadata: Map<String, String> = emptyMap()): SessionGrant
    fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult   // Live(remaining, owned) | Expired
    fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult>      // Granted(token) | HeldBy(session, token)
    fun release(session: SessionId, leases: Map<LeaseName, Long>)
    fun closeSession(session: SessionId)
    fun join(session: SessionId, group: String)
    fun leave(session: SessionId, group: String)
    fun members(group: String): List<SessionId>
    fun subscribe(listener: BackendHintListener): AutoCloseable   // optional Released / MembersChanged hints
}
```

每个实现都遵守的规则（由 [`LeaseBackendSpec`](/zh/guide/testing#backend-tck) 检查）：

- 每个方法都是一次后端往返（或每个 slot 一次），不做调度、不发通知、不保存本地状态；
- 每个剩余时长都用后端的时钟计算，并以时长而非时间戳返回；
- token 大于零，随该名称的每个新任期严格递增，且永不重置；
- 会话在其租约期内未被续约即过期，即使传输层仍保持连接；
- 过期的会话永不复活：`keepAlive` 返回 `Expired`，`acquire` 抛出 `SessionExpiredException`；
- `release` 只释放会话仍持有且 token 完全一致的租约，因此陈旧的释放是空操作；
- 提示（hint）只起加速作用；丢失提示从不影响正确性。

值类型：`LeaseName`（1–128 个 `[A-Za-z0-9._:-]` 字符，不能是 `.` 或 `..`）、`SessionId`（永不复用）——两者都是按值比较的普通不可变类，可在 Java 中使用——以及 `Ticker`（单调纳秒）。实现：`JdbcLeaseBackend`、
`RedisLeaseBackend`、`ZookeeperLeaseBackend`、`InMemoryLeaseBackend`；参阅 [后端](/zh/guide/backends)。

## Spring Boot — `me.ahoo.simba.spring.boot.starter` {#spring-boot-me-ahoo-simba-spring-boot-starter}

| 类型 | 说明 |
|---|---|
| `@SimbaScheduled(name, fixedDelay, fixedRate, initialDelay)` | 在 `election(name).schedule(...)` 上运行签名为 `()` 或 `(Term)` 的方法 |
| `@SimbaPartitioned(group, partitions, fixedDelay, fixedRate, initialDelay)` | 在 `group(group, partitions).schedule(...)` 上运行签名为 `(Term, Int)` 的方法 |
| `SimbaEndpoint` | Actuator 端点 `simba` |
| `SimbaHealthIndicator` | 健康指示器 `simba` |
| `MicrometerSimbaEventListener` | 基于 `SimbaEvent` 的 Micrometer 指标 |

参阅 [Spring Boot](/zh/guide/configuration) 和 [Leader 任务](/zh/guide/leader-work#spring-annotations)。
