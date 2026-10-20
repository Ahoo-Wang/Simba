---
title: 测试
description: 在合适的层次测试基于 Simba 的代码：内存后端、虚拟时间、确定性模拟、用于你的校验 token 资源的 FencingSpec，以及用于后端的 LeaseBackendSpec TCK。
---

# 测试

Leader 相关代码往往在缝隙中出错：租约在某一步中途过期，暂停期间被接管，两个节点短暂地意见不一。这些缝隙都与时间有关，
所以对一个测试来说，有用的问题是它需要对时间有多大的控制：

| 你想测试…… | 使用 | 时间 |
|---|---|---|
| 接受 `Simba` 的代码：选举、Leader 任务、锁 | `InMemoryLeaseBackend` | 真实时间，短租约 |
| 故障转移、过期、冷却期，无需等待 | `InMemoryLeaseBackend` + `VirtualScheduler` | 虚拟时间，确定性 |
| 引擎在故障下、跨越成千上万种交错的表现 | `Simulation` | 虚拟时间，按 seed |
| 你的资源会拒绝过期的持有者 | `FencingSpec` | — |
| 你自己的 `LeaseBackend` | `LeaseBackendSpec` | 后端自己的时间 |

```kotlin
testImplementation("me.ahoo.simba:simba-testing:${simbaVersion}")   // in-memory backend, virtual time, simulation
testImplementation("me.ahoo.simba:simba-tck:${simbaVersion}")       // FencingSpec, LeaseBackendSpec
```

示例使用 [FluentAssert](https://github.com/Ahoo-Wang/FluentAssert)（`.assert()`）断言，`simba-tck` 会带上它；只依赖
`simba-testing` 时，请另加 `me.ahoo.test:fluent-assert-core`，或使用你自己的断言库。

## 内存后端 {#in-memory-backend}

`InMemoryLeaseBackend` 在内存中实现完整的 `LeaseBackend` 契约：会话、token 永不重置的租约、组、释放以及成员变化提示。
它不需要任何基础设施，因此适合对任何接受 `Simba` 的代码做单元测试。它可以模拟每种存储风格：

| 构造方式 | 租约的行为类似 |
|---|---|
| `InMemoryLeaseBackend()` | JDBC、Zookeeper：租约与其会话同生共死 |
| `InMemoryLeaseBackend(selfExpiringLeases = true)` | Redis：租约自行过期，每次授予持续一个完整租期（与 Cluster 的租约槽授予相同） |
| `InMemoryLeaseBackend.builder().selfExpiringLeases(true).sessionBoundGrants(true)`（5.1 起） | 单个 Redis 节点：一次授予只持续其会话剩余的时间 |

```kotlin
@Test
fun `only one client leads`() {
    val backend = InMemoryLeaseBackend()
    val config = SimbaConfig.builder().leaseDuration(Duration.ofSeconds(3)).renewInterval(Duration.ofSeconds(1)).build()
    Simba.builder().backend(backend).config(config).build().use { first ->
        Simba.builder().backend(backend).config(config).build().use { second ->
            val elected = LinkedBlockingQueue<Term>()
            val listener = object : LeadershipListener {
                override fun onElected(term: Term) { elected += term }
                override fun onRevoked(term: Term, reason: RevokeReason) = Unit
            }
            first.election("job").start(listener)
            second.election("job").start(listener)

            val term = elected.poll(5, TimeUnit.SECONDS)!!
            backend.owner(term.name)!!.token.assert().isEqualTo(term.token)
        }
    }
}
```

`owner(name)` 和 `token(name)` 让测试可以直接检查后端；`peekOwner` 和 `peekExpiry` 读取时没有副作用，`expireSession` 和
`loseLease` 模拟服务器提前结束会话或存储丢失租约。在队列、latch 或 future 上等待，绝不使用 `sleep`。

在 Spring Boot 测试中，把后端定义为 bean：starter 会基于任何 `LeaseBackend` bean 构建 `Simba` 客户端，测试 classpath 上无需任何后端模块。

```kotlin
@TestConfiguration
class InMemorySimba {
    @Bean
    fun leaseBackend(): LeaseBackend = InMemoryLeaseBackend()
}
```

## 虚拟时间 {#virtual-time}

在真实时间上，故障转移测试仍要等待租约过期。要让此类测试瞬间且确定性地运行，请用同一个 `VirtualScheduler` 驱动客户端和后端：

```kotlin
@OptIn(InternalSimbaApi::class)
class FailoverTest {
    private val scheduler = VirtualScheduler()
    private val backend = InMemoryLeaseBackend(scheduler)

    private fun client(): Simba = Simba.builder()
        .backend(backend)
        .config(SimbaConfig.builder().leaseDuration(Duration.ofSeconds(3)).renewInterval(Duration.ofSeconds(1)).build())
        .scheduler(scheduler)          // clock and timers of the engine
        .ioExecutor(Runnable::run)     // backend calls inline
        .callbackExecutor(Runnable::run)
        .seed(0)
        .build()

    private fun advance(duration: Duration, vararg clients: Simba) =
        scheduler.advance(duration) { clients.forEach { it.awaitIdle() } }
}
```

`scheduler.advance(duration, settle)` 推进虚拟时间并按顺序触发每个到期的定时器，在每个定时器之前调用 `settle`，
使定时器触发的操作在时间继续推进前完成。Leader 任务仍在它自己的（虚拟）线程上运行，`awaitIdle()` 覆盖不到：通过队列等待它所做的事，
并在停止任务之前先等它启动。

`Simba.Builder.scheduler`、`Simba.awaitIdle` 和 `Scheduler` 标记为 `@InternalSimbaApi`：它们需要显式 opt-in，
且在次版本之间不提供兼容性保证。单独的 `VirtualClock` 是一个普通的 `Ticker`，适用于只读取时间的代码。

## 确定性模拟 {#deterministic-simulation}

`Simulation(seed, params).run()` 在虚拟时间中让多个引擎针对内存后端运行，同时注入关键的故障：

- 消息延迟与丢失、进程暂停、随时间变化的节点时钟速率；
- 加入和离开组（分区选举跟随各节点的视图），在故障期间关闭节点；
- 报告的时长短于实际授予，以及后端侧故障：后端单方面让会话过期、删除或丢失租约；可选择不发后端提示（与 JDBC 相同）。

然后它检查以下不变量：

| | 不变量 |
|---|---|
| I1 | 每个名称（包括分区）最多一个有效任期；每个有效任期的截止时刻不晚于后端记录的该租约过期时刻；任期一旦失效不会再变有效 |
| I2 | token 严格递增 |
| I3 | 回调成对出现 |
| I4 | 故障停止后名称和分区的活性；没有泄漏的租约 |
| I5 | 交接顺序 |

被后端侧故障移除的任期在撤销之前不受 I1 和 I2 约束，因为这类故障违反了后端的契约；租约丢失导致 token 重新计数时只做统计
（`premise:tokenReset`），不判失败，因为只有从不丢数据的后端才能保证 token 递增。`SimulationResult.events` 统计事件和被执行到的路径（`path:*`）。
Simba 在自己的构建中用 10⁴ 个 seed 运行模拟，失败的 seed 可以精确重放：

```bash
./gradlew simba-testing:test -Psimba.simulation.seed=42
```

`SimulationParams.builder()` 设置参数；以下参数在 5.1 中加入：

| `SimulationParams` | 默认值 | 含义 |
|---|---|---|
| `sessionBoundGrants` | `false` | 配合 `selfExpiringLeases`，一次授予只持续其会话剩余的时间，与 Redis 相同 |
| `truncatedGrantShare` | `0.3` | 后端报告的时长短于实际授予时长的运行所占比例（如 Zookeeper 服务器限制会话超时） |
| `meanInjectionInterval` | `20s` | 后端侧故障的平均间隔；`Duration.ZERO` 表示关闭 |
| `groupPartitions` | `3` | 节点随机加入和离开的一个组的分区数；`0` 表示不使用组 |
| `earlyCloseShare` | `0.02` | 故障阶段中永久关闭某个节点的随机操作所占比例 |
| `backendHints` | `true` | 后端发送释放和成员变化提示；`false` 模拟不发提示的 JDBC |

## 验证 fencing 资源 {#verifying-a-fencing-resource}

`FencingSpec` 检查一个用 fencing token 保护写入的资源（见 [Fencing 协议](/zh/guide/correctness#the-fencing-protocol)）。
继承它并实现两个方法：

```kotlin
class LedgerFencingTest : FencingSpec() {
    override fun write(resource: String, fencingToken: Long): Boolean = ledger.settle(resource, fencingToken)
    override fun highestToken(resource: String): Long = ledger.fencingToken(resource)

    // optional: also write with real terms of a backend
    override fun leaseBackend(): LeaseBackend = InMemoryLeaseBackend()
}
```

| 测试 | 验证内容 |
|---|---|
| `acceptsIncreasingTokens` | 递增的 token 被接受 |
| `acceptsTheSameTokenAgain` | 同一任期内的重试（相同 token）被接受 |
| `rejectsLowerToken` | 更低的 token 被拒绝，最高值保持不变 |
| `concurrentWritersNeverLetALowerTokenWin` | 并发写入者：最终总是最高的 token 胜出 |
| `staleTermIsRejectedAfterTheLockMovedOn` | 使用 `leaseBackend()` 时：一旦更新的任期写入过，已关闭的锁任期的 token 会被拒绝 |

## 后端 TCK {#backend-tck}

`LeaseBackendSpec` 是每个 `LeaseBackend` 都必须通过的契约：互斥、在释放后仍保留的严格递增 token、过期后保持过期的会话、
作为空操作的过期释放、批量获取、组成员只包含存活会话。为你自己的后端继承它：

```kotlin
class MyLeaseBackendTest : LeaseBackendSpec() {
    override fun createBackend(): LeaseBackend = MyLeaseBackend(connection)

    // optional: a backend with an injectable clock moves it instead of waiting
    override val clock: ClockControl? = null
    override val shortLease: Duration = Duration.ofSeconds(1)
}
```

| 覆盖项 | 默认值 | 含义 |
|---|---|---|
| `createBackend()` | 必填 | 基于被测存储的新后端；每个测试后关闭 |
| `lease` | 30 s | 必须比测试活得更久的会话的租约 |
| `shortLease` | 1 s | 测试会让其过期的会话的租约；在真实时间上请保持较短 |
| `clock` | `null` | 测试可控制时后端的时钟；否则基于时间的测试会轮询直到租约耗尽 |
| `timeout` | 30 s | 测试等待任何事物的上限 |

每个测试都使用全新的租约名和组名，因此存储可以在测试之间共享。JDBC、Redis 和 Zookeeper 后端在 Simba 自己的构建中运行此规范；
见[参与贡献](/zh/contributing/#adding-a-backend)。
