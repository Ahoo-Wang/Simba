---
title: Spring Boot
description: 所有 simba.* 属性、Spring Boot starter 创建的 bean、关闭顺序，以及如何在不使用 Spring 的情况下构建 Simba 客户端。
---

# Spring Boot

`simba-spring-boot-starter` 基于 classpath 上的后端构建一个 `Simba` 客户端，运行 `@SimbaScheduled` 和
`@SimbaPartitioned` 方法，并提供 Micrometer 指标、`simba` Actuator 端点和 `simba` 健康指示器。

## 属性 {#properties}

| 属性 | 默认值 | 说明 |
|---|---|---|
| `simba.enabled` | `true` | 启用整个自动配置 |
| `simba.backend` | — | `jdbc`、`redis` 或 `zookeeper`；有多个后端模块生效时必须设置 |
| `simba.session.lease-duration` | `15s` | 会话最后一次续约 (keep-alive) 之后，后端经过这段时间使会话过期 |
| `simba.session.renew-interval` | `lease-duration / 3` | 续约 (keep-alive) 周期；最多为租约时长的一半 |
| `simba.session.drift` | `0.01` | 节点与后端之间时钟速率差异的上限，取值范围 `[0, 0.5)` |
| `simba.session.margin` | `50ms` | 从每个本地截止时刻中额外扣除的安全余量 |
| `simba.jdbc.enabled` | `true` | 启用 JDBC 后端 |
| `simba.jdbc.dialect` | 自动检测 | `mysql` 或 `postgresql`；未设置时根据 `DataSource` 检测 |
| `simba.jdbc.query-timeout` | 续约周期 | 单条语句的超时时间 |
| `simba.redis.enabled` | `true` | 启用 Redis 后端 |
| `simba.redis.wait-replicas` | `0` | 为正数时，新任期只有在这么多副本确认（`WAIT`）后才算数；不适用于 Cluster |
| `simba.redis.wait-timeout` | `1s` | `WAIT` 的等待时长 |
| `simba.redis.route-by-slot` | Cluster 上为 `true` | 按 hash slot 拆分脚本调用 |
| `simba.zookeeper.enabled` | `true` | 启用 Zookeeper 后端 |
| `simba.zookeeper.connect-string` | `localhost:2181` | 后端在其上打开会话的 ensemble |
| `simba.zookeeper.root` | `/simba` | 租约节点和组节点的父路径 |
| `simba.zookeeper.connection-timeout` | `15s` | 打开一个会话允许花费的时间 |
| `simba.scheduling.enabled` | `true` | 运行 `@SimbaScheduled` 和 `@SimbaPartitioned` 方法 |
| `simba.metrics.enabled` | `true` | 存在 `MeterRegistry` bean 时记录 Micrometer 指标 |
| `management.health.simba.enabled` | `true` | `simba` 健康指示器 |

会话相关属性的含义见[核心概念与正确性](/zh/guide/correctness#timing)；无效的组合会导致启动失败。典型配置：

```yaml
simba:
  backend: redis            # only needed with several backend modules on the classpath
  session:
    lease-duration: 15s
management:
  endpoints:
    web:
      exposure:
        include: health,simba
```

## Starter 创建的 Bean {#beans-the-starter-creates}

| Bean | 条件 |
|---|---|
| `LeaseBackend`（`JdbcLeaseBackend`、`RedisLeaseBackend` 或 `ZookeeperLeaseBackend`） | 该后端生效且应用未定义 `LeaseBackend`；JDBC 需要唯一的 `DataSource`，Redis 需要唯一的 `RedisConnectionFactory` |
| `Simba` | 存在 `LeaseBackend` bean；使用 `simba.session.*` 和所有 `SimbaEventListener` bean 构建 |
| `SimbaLifecycle` | 在关闭时关闭客户端 |
| `SimbaScheduledBeanPostProcessor` | `simba.scheduling.enabled` |
| `MicrometerSimbaEventListener` | classpath 上有 Micrometer、存在 `MeterRegistry` bean、`simba.metrics.enabled` |
| `SimbaEndpoint`（`simba`） | classpath 上有 Actuator 且端点可用（已启用并已暴露） |
| `SimbaHealthIndicator`（`simba`） | classpath 上有 Actuator 且 `management.health.simba.enabled` |

所有 bean 都是 `@ConditionalOnMissingBean`：自行定义即可替换。若要在属性之外定制客户端（执行器、固定的
seed），请用 `Simba.builder()` 自行定义 `Simba` bean。

## 注解方法 {#annotated-methods}

`@SimbaScheduled` 在某个选举的 leader 上运行一个 bean 方法；`@SimbaPartitioned` 为本节点作为 leader 的组内每个分区运行该方法。
两者都在上下文刷新后启动、在关闭时停止。它们的属性和方法签名见 [Leader 任务与分区](/zh/guide/leader-work#spring-annotations)页面。

## 关闭顺序 {#shutdown-order}

1. `SimbaScheduledBeanPostProcessor`（phase `DEFAULT_PHASE`）停止所有注解方法：任务被中断，每个租约在其任务退出后释放
   （见[交接顺序](/zh/guide/correctness#handover-order)）。
2. 随后 `SimbaLifecycle`（phase `DEFAULT_PHASE - 4096`）关闭 `Simba` 客户端：撤销所有剩余任期、关闭会话并释放其租约，
   这发生在连接工厂等更低 phase 的基础设施停止之前。

你自己启动的选举和锁应在此之前停止，例如在 `ContextClosedEvent` 监听器中；即便没有停止，关闭客户端也会停止它们。

## 不使用 Spring {#without-spring}

用 `Simba.builder()` 构建客户端：

```kotlin
val simba = Simba.builder()
    .backend(JdbcLeaseBackend.mysql(dataSource, queryTimeout = Duration.ofSeconds(5)))
    .config(SimbaConfig(leaseDuration = Duration.ofSeconds(15), renewInterval = Duration.ofSeconds(5)))
    .listener(myEventListener)
    .build()
```

| Builder 方法 | 默认值 | 说明 |
|---|---|---|
| `backend(LeaseBackend)` | 必填 | 后端；客户端不会关闭它 |
| `config(SimbaConfig)` | `SimbaConfig()` | 会话时序，见[时序](/zh/guide/correctness#timing) |
| `listener(SimbaEventListener)` | 无 | 接收每个 [`SimbaEvent`](/zh/guide/observability#events)；可多次调用 |
| `ioExecutor(Executor)` | 每次调用一个虚拟线程 | 执行后端调用 |
| `callbackExecutor(Executor)` | 虚拟线程 | 执行 leader 身份回调，每个选举内串行 |
| `workExecutor(Executor)` | 每个任期一个虚拟线程 | 执行 leader 任务 |
| `ticker(Ticker)` | `System.nanoTime()` | 单调时钟；推进速率必须与 `System.nanoTime()` 一致 |
| `seed(Long)` | 随机 | 重试抖动的 seed |

**每个进程创建一个客户端**。一个进程中的多个客户端就是多个会话：仅在需要让几组租约互不受对方续约失败影响时才有用，
代价是每个客户端各自一次续约 (keep-alive)。`close()`（或 `closeAsync()`）撤销所有任期、关闭会话，
并在最后一个回调执行完后完成；之后再关闭后端。
