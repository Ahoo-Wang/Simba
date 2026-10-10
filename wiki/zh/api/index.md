---
title: API 参考
description: Simba 的公共类型——竞争服务、竞争者、持有者、locker、调度器、观察者和后端 SPI。
---

# API 参考

除特别说明外，所有类型都位于 `simba-core`。包 `me.ahoo.simba` 简写为 `simba`。

## 竞争

### `MutexContendServiceFactory` — `simba.core`

```kotlin
interface MutexContendServiceFactory {
    fun createMutexContendService(mutexContender: MutexContender): MutexContendService
}
```

所有 API 的入口。实现：`JdbcMutexContendServiceFactory`、`SpringRedisMutexContendServiceFactory`、
`ZookeeperMutexContendServiceFactory`（见 [配置](/zh/guide/configuration#不使用-spring)）。JDBC 和 Redis 工厂实现了
`AutoCloseable` 并拥有自己的执行器。

### `MutexContender` / `AbstractMutexContender` — `simba.core`

| 成员 | 说明 |
|---|---|
| `mutex: String` | mutex 名称。同名的竞争者相互竞争。 |
| `contenderId: String` | 该竞争者的唯一 id。默认：`ContenderIdGenerator.HOST`（`counter:pid@host`）。 |
| `onAcquired(mutexState)` | 该竞争者成为持有者。 |
| `onReleased(mutexState)` | 该竞争者不再是持有者（释放、丢失、撤销或停止）。 |

继承 `AbstractMutexContender(mutex, contenderId = ...)`；它会校验这两个值并记录回调日志。回调在 handle executor 上运行，
对同一个竞争者按顺序执行且从不并发。

### `MutexContendService` — `simba.core`

由工厂为一个竞争者创建；实现 `AutoCloseable`。

| 成员 | 说明 |
|---|---|
| `start()` / `stop()` | 开始或停止竞争。`stop()` 在非 `RUNNING` 时抛出异常；若为持有者则投递 `onReleased`，并在其执行完后返回。 |
| `close()` | 幂等的 `stop()`。 |
| `status` | `INITIAL → STARTING → RUNNING → STOPPING → INITIAL`。`start()` 失败会回到 `INITIAL`；服务可以重启。 |
| `running` | `status` 为 `STARTING` 或 `RUNNING`。 |
| `isOwner` | 该竞争者持有 mutex（本地弱一致视图）。 |
| `isInTtl` | 是持有者且在 `ttlAt` 之前。 |
| `fencingToken` | 作为持有者时当前任期的 token，否则为 `0`。 |
| `afterOwner` / `beforeOwner` / `mutexState` | 最近观察到的持有者及其前一个。 |
| `hasOwner()` | 有人持有尚未结束的租约。 |

### `MutexState` 与 `MutexOwner` — `simba.core`

`MutexState(before, after)` 传给回调；`isAcquired(id)` / `isReleased(id)` 指出发生了哪种转换。

`MutexOwner` 是描述租约的不可变值：

| 字段 | 说明 |
|---|---|
| `ownerId` | 持有者的 contender id；`MutexOwner.NONE` 时为 `""`。 |
| `acquiredAt`、`ttlAt`、`transitionAt` | 后端时钟上的毫秒时间戳。Zookeeper 为 `Long.MAX_VALUE`（没有租约）。 |
| `fencingToken` | 任期的 token，没有时为 `0`。 |
| `observedAt`、`currentAt` | 观察时的后端时间，以及用本地单调时钟推进后的该时间。 |

相等性比较忽略 `observedAt`。

## 锁 — `simba.locker`

```kotlin
interface Locker : AutoCloseable {
    fun acquire()                      // 等待直到成为持有者
    fun acquire(timeout: Duration)     // 超时抛出 TimeoutException
    val fencingToken: Long
}
class SimbaLocker(mutex: String, contendServiceFactory: MutexContendServiceFactory) : Locker
```

`close()` 停止竞争并释放 mutex。一个 locker 同一时间只属于一个线程（另一个线程调用 `acquire` 会抛出
`IllegalMonitorStateException`）；中断不会取消 `acquire()`，中断标志会被恢复。`acquire` 失败会关闭 locker。

## 调度 — `simba.schedule`

### `SimbaScheduler`

```kotlin
class SimbaScheduler(
    mutex: String,
    contendServiceFactory: MutexContendServiceFactory,
    config: ScheduleConfig,
    worker: String = mutex,          // 线程名前缀
    work: ScheduledWork              // fun interface: work(context: ScheduleContext)
) : AutoCloseable
```

成员：`start()`、`stop()`、幂等的 `close()`、`running`、`isLeader`、`fencingToken`。节点获取 mutex 时创建一个单线程执行器运行工作；
释放时以中断方式取消运行。从未成为 leader 的节点不持有工作线程。

`ScheduleConfig.delay(initialDelay, period)` 以固定延迟运行；`ScheduleConfig.rate(initialDelay, period)` 以固定频率运行。
`ScheduleContext` 提供 `mutex` 和 `fencingToken`。某次运行失败会被记录日志，后续运行照常进行。

`AbstractScheduler(mutex, factory)` 以继承方式提供相同语义（`config`、`worker`、`work()`、protected `fencingToken`）；
推荐使用 `SimbaScheduler`。

### `@SimbaScheduled` — `simba-spring-boot-starter`，`simba.spring.boot.starter.scheduling`

| 属性 | 说明 |
|---|---|
| `mutex` | 由其 leader 运行该方法的 mutex；在应用内唯一。 |
| `fixedDelay` / `fixedRate` | 必须且只能设置一个。Spring Boot 时长格式，支持占位符。 |
| `initialDelay` | 成为 leader 后的延迟。默认 `0s`。 |
| `worker` | 线程名前缀。默认：`mutex`。 |

方法不接受参数，或只接受一个 `ScheduleContext`。Starter 为每个方法注册一个 `SimbaScheduler`，在上下文刷新后启动、关闭时停止；
`SimbaScheduler` bean 也按同样方式管理。声明无效、mutex 重复或缺少 `MutexContendServiceFactory` 都会导致启动失败。

## 观察 — `simba.core`

`ContendObserver` 接收竞争服务的事件；每个方法默认是空操作，异常会被记录并忽略。将它传给工厂（或在使用 starter 时声明为 bean）。

| 方法 | 时机 | 后端 |
|---|---|---|
| `onStarted(service)` / `onStopped(service)` | `start()` 成功 / `stop()` 完成 | 全部 |
| `onContend(mutex, renew, durationNanos, outcome)` | 一次后端往返结束：`OWNER`、`OTHER` 或 `FAILED` | JDBC、Redis |
| `onAcquired(mutex)` / `onReleased(mutex)` | 该竞争者获得 / 失去所有权 | 全部 |
| `onLeaseExpired(mutex)` | 租约看门狗撤销了所有权；随后是 `onReleased` | JDBC、Redis |
| `onWork(mutex, durationNanos, outcome)` | 一次调度运行结束：`SUCCESS`、`FAILED` 或 `INTERRUPTED` | 全部 |

基于它的 Micrometer 指标和 Actuator 端点见 [可观测性](/zh/guide/observability)。

## 后端 SPI — `simba.core`

要添加一个轮询类后端，实现一个原子存储调用并复用引擎：

```kotlin
interface MutexLeaseStore {
    /** 获取，或在 renew 为 true 时续期；返回操作后后端观察到的持有者。 */
    fun contend(mutex: String, contenderId: String, renew: Boolean, config: LeaseConfig): MutexOwner
    /** 由 contenderId 持有时释放；本次调用释放成功时返回 true。 */
    fun release(mutex: String, contenderId: String): Boolean
}
```

`LeaseContendService(contender, handleExecutor, leaseStore, leaseConfig, scheduler, ioExecutor, observer)` 为其运行竞争循环、
租约看门狗和生命周期。`LeaseConfig(ttl, transition, initialDelay)` 校验时长。`ContendExecutors` 创建默认的调度器、I/O 和回调执行器。
见 [参与贡献](/zh/contributing/#添加后端)。
