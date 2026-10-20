---
title: 测试
description: 用内存后端和虚拟时间测试使用 Simba 的代码，用 FencingSpec 验证 fencing 资源，并用 LeaseBackendSpec TCK 检查后端。
---

# 测试

两个测试模块帮助你测试基于 Simba 构建的代码，以及 Simba 所依赖的代码：

| 模块 | 内容 |
|---|---|
| `simba-testing` | `InMemoryLeaseBackend`、`VirtualClock`、`VirtualScheduler`、`Simulation` |
| `simba-tck` | `FencingSpec`（用于你的校验 token 的资源）和 `LeaseBackendSpec`（用于后端） |

```kotlin
testImplementation("me.ahoo.simba:simba-testing:${simbaVersion}")
testImplementation("me.ahoo.simba:simba-tck:${simbaVersion}")
```

示例使用 [FluentAssert](https://github.com/Ahoo-Wang/FluentAssert)（`.assert()`）断言，`simba-tck` 会带上它；只依赖
`simba-testing` 时，请另加 `me.ahoo.test:fluent-assert-core`，或使用你自己的断言库。

## 内存后端 {#in-memory-backend}

`InMemoryLeaseBackend` 在内存中实现完整的 `LeaseBackend` 契约（会话、token 永不重置的租约、组、释放以及成员变化提示）。
它不需要任何基础设施，因此适合对任何接受 `Simba` 的代码做单元测试：

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

`owner(name)` 和 `token(name)` 让测试可以直接检查后端。在队列、latch 或 future 上等待，绝不使用 `sleep`。

在 Spring Boot 测试中，把后端定义为 bean：starter 会基于任何 `LeaseBackend` bean 构建 `Simba` 客户端，测试 classpath 上无需任何后端模块。

```kotlin
@TestConfiguration
class InMemorySimba {
    @Bean
    fun leaseBackend(): LeaseBackend = InMemoryLeaseBackend()
}
```

## 虚拟时间 {#virtual-time}

内存后端运行在真实时间上时，故障转移测试仍要等待租约过期。要让此类测试瞬间且确定性地运行，请用同一个 `VirtualScheduler` 驱动客户端和后端：

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
使定时器触发的操作在时间继续推进前完成。leader 任务仍在它自己的（虚拟）线程上运行；通过队列等待它的结果。

`Simba.Builder.scheduler`、`Simba.awaitIdle` 和 `Scheduler` 标记为 `@InternalSimbaApi`：它们需要显式 opt-in，
且在次版本之间不提供兼容性保证。单独的 `VirtualClock` 是一个普通的 `Ticker`，适用于只读取时间的代码。

## 确定性模拟 {#deterministic-simulation}

`Simulation(seed, params).run()` 在虚拟时间中让多个引擎针对内存后端运行，同时注入消息延迟与丢失、进程暂停、随时间变化的节点时钟速率、
随机加入和离开分组（分区选举跟随各节点的视图）、在故障期间关闭节点、报告的剩余时间短于实际授予、后端侧故障（后端单方面让会话过期、
删除或丢失租约），并可选择不发后端提示（模拟 JDBC）。它检查以下不变量：每个名称（包括分区）最多一个有效任期、每个有效任期的截止时刻不晚于
后端记录的租约过期时刻、任期失效后不会再变有效、token 严格递增、回调成对出现、故障停止后名称和分区的活性、没有泄漏的租约，以及交接顺序。
被后端侧故障移除的任期在撤销之前不受前几条检查约束，因为这类故障违反了后端的契约；租约丢失导致 token 重新计数时只做统计
（`premise:tokenReset`），不判失败，因为只有从不丢数据的后端才能保证 token 递增。
`SimulationParams.builder()` 设置参数，`SimulationResult.events` 统计事件和被执行到的路径（`path:*`）。Simba 在自己的构建中用 10⁴ 个 seed 运行它；任何修改引擎的人都可以使用它，失败的 seed 可以精确重放：

```bash
./gradlew simba-testing:test -Psimba.simulation.seed=42
```

## 验证 fencing 资源 {#verifying-a-fencing-resource}

`FencingSpec` 检查一个用 fencing token 保护写入的资源（见[在资源中校验 token](/zh/guide/correctness#checking-tokens-in-the-resource)）。
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
