---
title: Configuration
description: Every simba.* Spring Boot property, the callback executor, and the backend factories for use without Spring.
---

# Configuration

For what `ttl` and `transition` mean and how to choose them, read [Correctness](/guide/correctness#choosing-ttl-and-transition).

## Spring Boot Properties

| Property | Default | Description |
|---|---|---|
| `simba.enabled` | `true` | Master switch for all Simba auto-configuration. |
| `simba.backend` | — | `jdbc`, `redis` or `zookeeper`. Required when more than one backend is active; startup fails otherwise. Must name an active backend. |
| `simba.jdbc.enabled` | `true` | Enable the JDBC backend. |
| `simba.jdbc.initial-delay` | `0s` | Delay before the first contention after `start()`. |
| `simba.jdbc.ttl` | `10s` | Lease length until the owner renews. Also used as the JDBC statement timeout. |
| `simba.jdbc.transition` | `6s` | Grace period after `ttl` in which only the owner may renew. |
| `simba.jdbc.fencing` | `true` | Issue fencing tokens from the `fencing_token` column; set `false` for tables without it. |
| `simba.redis.enabled` | `true` | Enable the Redis backend. |
| `simba.redis.ttl` | `10s` | Lease length until the owner renews. |
| `simba.redis.transition` | `6s` | Grace period after `ttl` in which only the owner may renew. |
| `simba.zookeeper.enabled` | `true` | Enable the Zookeeper backend. Timing is governed by the Curator session. |
| `simba.scheduling.enabled` | `true` | Run `@SimbaScheduled` methods and `SimbaScheduler` beans with the application context. |
| `simba.metrics.enabled` | `true` | Record Micrometer metrics when a `MeterRegistry` bean exists; see [Observability](/guide/observability). |

Durations accept Spring Boot formats (`10s`, `500ms`, `PT1M`). `ttl` must be positive, `transition` and
`initial-delay` must not be negative.

## Beans the Starter Creates

| Bean | Condition | Override or disable |
|---|---|---|
| `MutexContendServiceFactory` | The selected backend's infrastructure bean exists: a single `DataSource`, a `StringRedisTemplate`, or a `CuratorFramework` | Defining your own `MutexContendServiceFactory` |
| `MutexOwnerRepository` (JDBC) | A single `DataSource` | Defining your own `MutexOwnerRepository` |
| `RedisMessageListenerContainer` (Redis) | A single `RedisConnectionFactory` | Defining your own container |
| `simbaHandleExecutor` | Always | A bean with the same name |
| `MicrometerContendObserver` | Micrometer and a `MeterRegistry` bean | `simba.metrics.enabled=false` |
| `SimbaEndpoint` | Actuator, endpoint enabled and exposed | Actuator `management.*` properties |

Every `ContendObserver` bean in the context is passed to the backend factory, in `@Order`.

### Callback Executor

`onAcquired` and `onReleased` run on `simbaHandleExecutor`, a dedicated daemon pool whose idle threads are reclaimed.
Callbacks of one contender never run concurrently, so the pool grows only with the number of contenders notified at
the same moment. To use your own executor:

```kotlin
@Bean(name = [SimbaAutoConfiguration.HANDLE_EXECUTOR_BEAN_NAME]) // "simbaHandleExecutor"
fun simbaHandleExecutor(): ExecutorService = Executors.newFixedThreadPool(2)
```

## Without Spring

Each backend has a factory. Factories of polling backends own a scheduler (contention triggers and lease watchdogs) and
an I/O executor (backend calls), both daemon and idle-reclaimed; `close()` the factory on shutdown. Without
`handleExecutor`, callbacks run on `ForkJoinPool.commonPool()`; pass a dedicated executor if callbacks may block.

::: code-group

```kotlin [JDBC]
val factory = JdbcMutexContendServiceFactory(
    mutexOwnerRepository = JdbcMutexOwnerRepository(
        dataSource,
        queryTimeout = Duration.ofSeconds(10), // default: no timeout
        fencing = true
    ),
    handleExecutor = callbackExecutor,
    initialDelay = Duration.ZERO,
    ttl = Duration.ofSeconds(10),
    transition = Duration.ofSeconds(6),
    observer = myObserver // optional
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
    observer = myObserver // optional
)
```

```kotlin [Zookeeper]
val factory = ZookeeperMutexContendServiceFactory(
    handleExecutor = callbackExecutor,
    curatorFramework = curatorFramework, // already started
    observer = myObserver // optional
)
```

:::

All factory and service constructors are `@JvmOverloads`, so Java callers can omit trailing optional parameters.
