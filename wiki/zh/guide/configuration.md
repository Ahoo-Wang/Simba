---
title: 配置
description: 所有 simba.* Spring Boot 属性、回调执行器，以及不使用 Spring 时的后端工厂。
---

# 配置

`ttl` 和 `transition` 的含义及取值方法，请阅读 [正确性](/zh/guide/correctness#选择-ttl-和-transition)。

## Spring Boot 属性

| 属性 | 默认值 | 说明 |
|---|---|---|
| `simba.enabled` | `true` | 所有 Simba 自动配置的总开关。 |
| `simba.backend` | — | `jdbc`、`redis` 或 `zookeeper`。激活多个后端时必须设置，否则启动失败。必须指向一个已激活的后端。 |
| `simba.jdbc.enabled` | `true` | 启用 JDBC 后端。 |
| `simba.jdbc.initial-delay` | `0s` | `start()` 之后首次竞争前的延迟。 |
| `simba.jdbc.ttl` | `10s` | 持有者续期前的租约长度。同时用作 JDBC 语句超时。 |
| `simba.jdbc.transition` | `6s` | `ttl` 之后只有持有者可以续期的宽限期。 |
| `simba.jdbc.fencing` | `true` | 从 `fencing_token` 列签发 fencing token；没有该列的表请设为 `false`。 |
| `simba.redis.enabled` | `true` | 启用 Redis 后端。 |
| `simba.redis.ttl` | `10s` | 持有者续期前的租约长度。 |
| `simba.redis.transition` | `6s` | `ttl` 之后只有持有者可以续期的宽限期。 |
| `simba.zookeeper.enabled` | `true` | 启用 Zookeeper 后端。时序由 Curator 会话决定。 |
| `simba.scheduling.enabled` | `true` | 随应用上下文运行 `@SimbaScheduled` 方法和 `SimbaScheduler` bean。 |
| `simba.metrics.enabled` | `true` | 存在 `MeterRegistry` bean 时记录 Micrometer 指标；见 [可观测性](/zh/guide/observability)。 |

时长支持 Spring Boot 格式（`10s`、`500ms`、`PT1M`）。`ttl` 必须为正，`transition` 和 `initial-delay` 不能为负。

## Starter 创建的 Bean

| Bean | 条件 | 覆盖或关闭 |
|---|---|---|
| `MutexContendServiceFactory` | 所选后端的基础设施 bean 存在：单个 `DataSource`、一个 `StringRedisTemplate` 或一个 `CuratorFramework` | 定义自己的 `MutexContendServiceFactory` |
| `MutexOwnerRepository`（JDBC） | 单个 `DataSource` | 定义自己的 `MutexOwnerRepository` |
| `RedisMessageListenerContainer`（Redis） | 单个 `RedisConnectionFactory` | 定义自己的 container |
| `simbaHandleExecutor` | 总是 | 定义同名 bean |
| `MicrometerContendObserver` | Micrometer 且存在 `MeterRegistry` bean | `simba.metrics.enabled=false` |
| `SimbaEndpoint` | Actuator，端点已启用并暴露 | Actuator 的 `management.*` 属性 |

上下文中所有 `ContendObserver` bean 都会按 `@Order` 传给后端工厂。

### 回调执行器

`onAcquired` 和 `onReleased` 在 `simbaHandleExecutor` 上运行，这是一个专用的守护线程池，空闲线程会被回收。
同一个竞争者的回调从不并发执行，因此线程池只随同一时刻被通知的竞争者数量增长。使用自己的执行器：

```kotlin
@Bean(name = [SimbaAutoConfiguration.HANDLE_EXECUTOR_BEAN_NAME]) // "simbaHandleExecutor"
fun simbaHandleExecutor(): ExecutorService = Executors.newFixedThreadPool(2)
```

## 不使用 Spring

每个后端都有一个工厂。轮询类后端的工厂拥有一个调度器（竞争触发和租约看门狗）和一个 I/O 执行器（后端调用），两者都是守护线程且空闲回收；
关闭时请 `close()` 工厂。不传 `handleExecutor` 时，回调运行在 `ForkJoinPool.commonPool()` 上；如果回调可能阻塞，请传入专用执行器。

::: code-group

```kotlin [JDBC]
val factory = JdbcMutexContendServiceFactory(
    mutexOwnerRepository = JdbcMutexOwnerRepository(
        dataSource,
        queryTimeout = Duration.ofSeconds(10), // 默认：无超时
        fencing = true
    ),
    handleExecutor = callbackExecutor,
    initialDelay = Duration.ZERO,
    ttl = Duration.ofSeconds(10),
    transition = Duration.ofSeconds(6),
    observer = myObserver // 可选
)
```

```kotlin [Redis]
val listenerContainer = RedisMessageListenerContainer().apply {
    setConnectionFactory(connectionFactory)
    afterPropertiesSet()
    start()
}
val factory = SpringRedisMutexContendServiceFactory(
    ttl = Duration.ofSeconds(10),
    transition = Duration.ofSeconds(6),
    redisTemplate = StringRedisTemplate(connectionFactory),
    listenerContainer = listenerContainer,
    handleExecutor = callbackExecutor,
    observer = myObserver // 可选
)
```

```kotlin [Zookeeper]
val factory = ZookeeperMutexContendServiceFactory(
    handleExecutor = callbackExecutor,
    curatorFramework = curatorFramework, // 已启动
    observer = myObserver // 可选
)
```

:::

所有工厂和服务的构造函数都标注了 `@JvmOverloads`，Java 调用方可以省略末尾的可选参数。
