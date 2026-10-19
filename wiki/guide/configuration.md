---
title: Spring Boot
description: Every simba.* property, the beans the Spring Boot starter creates, the shutdown order, and how to build the Simba client without Spring.
---

# Spring Boot

`simba-spring-boot-starter` builds one `Simba` client over the backend on the classpath, runs `@SimbaScheduled` and
`@SimbaPartitioned` methods, and adds Micrometer metrics, the `simba` Actuator endpoint and the `simba` health
indicator.

## Properties

| Property | Default | Description |
|---|---|---|
| `simba.enabled` | `true` | Enables the whole auto-configuration |
| `simba.backend` | — | `jdbc`, `redis` or `zookeeper`; required when several backend modules are active |
| `simba.session.lease-duration` | `15s` | The backend expires the session this long after its last keep-alive |
| `simba.session.renew-interval` | `lease-duration / 3` | Keep-alive period; at most half of the lease duration |
| `simba.session.drift` | `0.01` | Upper bound of the clock rate difference between a node and the backend, in `[0, 0.5)` |
| `simba.session.margin` | `50ms` | Extra safety subtracted from every local deadline |
| `simba.jdbc.enabled` | `true` | Enables the JDBC backend |
| `simba.jdbc.dialect` | detected | `mysql` or `postgresql`; detected from the `DataSource` when unset |
| `simba.jdbc.query-timeout` | renew interval | Per-statement timeout |
| `simba.redis.enabled` | `true` | Enables the Redis backend |
| `simba.redis.wait-replicas` | `0` | When positive, a new term counts only once that many replicas acknowledged it (`WAIT`); not on a Cluster |
| `simba.redis.wait-timeout` | `1s` | How long `WAIT` waits |
| `simba.redis.route-by-slot` | `true` on a Cluster | Split script calls by hash slot |
| `simba.zookeeper.enabled` | `true` | Enables the Zookeeper backend |
| `simba.zookeeper.connect-string` | `localhost:2181` | Ensemble the backend opens its sessions on |
| `simba.zookeeper.root` | `/simba` | Parent path of the lease and group nodes |
| `simba.zookeeper.connection-timeout` | `15s` | How long opening a session may take |
| `simba.zookeeper.auth.scheme` | `digest` | Authentication scheme every Zookeeper client adds |
| `simba.zookeeper.auth.credentials` | — | Auth data of the scheme (UTF-8), e.g. `user:password` for `digest`; unset: no authentication |
| `simba.zookeeper.acl` | `auto` | ACL of the nodes the backend creates: `open` (`OPEN_ACL_UNSAFE`), `creator` (`CREATOR_ALL_ACL`, needs credentials) or `auto` (`creator` with credentials, else `open`); an `ACLProvider` bean takes precedence |
| `simba.scheduling.enabled` | `true` | Runs `@SimbaScheduled` and `@SimbaPartitioned` methods |
| `simba.metrics.enabled` | `true` | Records Micrometer metrics when a `MeterRegistry` bean exists |
| `management.health.simba.enabled` | `true` | The `simba` health indicator |

The session properties are explained in [Concepts and Correctness](/guide/correctness#timing); invalid combinations
fail startup. A typical configuration:

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

## Beans the Starter Creates

| Bean | Condition |
|---|---|
| `LeaseBackend` (`JdbcLeaseBackend`, `RedisLeaseBackend` or `ZookeeperLeaseBackend`) | The backend is active and the application defines no `LeaseBackend`; JDBC needs a single `DataSource`, Redis a single `RedisConnectionFactory`; Zookeeper uses an `ACLProvider` and a `ZKClientConfig` bean when present |
| `Simba` | A `LeaseBackend` bean exists; built with `simba.session.*` and every `SimbaEventListener` bean |
| `SimbaLifecycle` | Closes the client on shutdown |
| `SimbaScheduledBeanPostProcessor` | `simba.scheduling.enabled` |
| `MicrometerSimbaEventListener` | Micrometer on the classpath, a `MeterRegistry` bean, `simba.metrics.enabled` |
| `SimbaEndpoint` (`simba`) | Actuator on the classpath and the endpoint available (enabled and exposed) |
| `SimbaHealthIndicator` (`simba`) | Actuator on the classpath and `management.health.simba.enabled` |

Every bean except the infrastructure (`SimbaBackendSelection`, the startup check of `simba.backend`, and
`SimbaScheduledBeanPostProcessor`) is `@ConditionalOnMissingBean`: define your own to replace it. To customize the
client beyond the properties (executors, a fixed seed), define the `Simba` bean yourself with `Simba.builder()`.

## Annotated Methods

`@SimbaScheduled` runs a bean method on the leader of an election; `@SimbaPartitioned` runs it for every partition of
a group this node leads. Both start after the context refreshed and stop on shutdown. Their attributes and method
signatures are on the [Leader Work](/guide/leader-work#spring-annotations) page. The lease names they elect (the
`name` of `@SimbaScheduled`, `{group}.{partition}` of `@SimbaPartitioned`) and the groups must be unique across both
annotations: startup fails on a conflict.

## Shutdown Order

1. `SimbaScheduledBeanPostProcessor` (phase `DEFAULT_PHASE`) stops every annotated method: work is interrupted, and
   each lease is released once its work exited (see [Handover Order](/guide/correctness#handover-order)).
2. `SimbaLifecycle` (phase `DEFAULT_PHASE - 4096`) then closes the `Simba` client, which revokes every remaining term,
   closes the session and frees its leases, before lower-phase infrastructure such as connection factories stops.

Elections and locks you started yourself should be stopped before that, e.g. in a `ContextClosedEvent` listener;
closing the client stops them anyway.

## Without Spring

Build the client with `Simba.builder()`:

```kotlin
val simba = Simba.builder()
    .backend(JdbcLeaseBackend.mysql(dataSource, queryTimeout = Duration.ofSeconds(5)))
    .config(SimbaConfig.builder().leaseDuration(Duration.ofSeconds(15)).renewInterval(Duration.ofSeconds(5)).build())
    .listener(myEventListener)
    .build()
```

| Builder method | Default | Description |
|---|---|---|
| `backend(LeaseBackend)` | required | The backend; the client does not close it |
| `config(SimbaConfig)` | `SimbaConfig.builder().build()` | Session timing, see [Timing](/guide/correctness#timing) |
| `listener(SimbaEventListener)` | none | Receives every [`SimbaEvent`](/guide/observability#events); may be called several times |
| `ioExecutor(Executor)` | a virtual thread per call | Runs backend calls |
| `callbackExecutor(Executor)` | virtual threads | Runs leadership callbacks, serially per election |
| `workExecutor(Executor)` | a virtual thread per term | Runs leader work |
| `ticker(Ticker)` | `System.nanoTime()` | Monotonic clock; must advance at the rate of `System.nanoTime()` |
| `seed(Long)` | random | Seed of the retry jitter |

Create **one client per process**. Several clients in one process are several sessions: useful only to isolate
groups of leases from each other's renewal failures, at the cost of one keep-alive each. `close()` (or
`closeAsync()`) revokes every term, closes the session and completes after the last callback ran; close the backend
afterwards.
