---
title: Upgrading
description: Simba's versioning policy, how to upgrade within 5.x, and where to find the 4.x to 5.0 migration.
---

# Upgrading

Simba follows [Semantic Versioning](https://semver.org/) at the source level: within a major version, code that
compiled against an earlier release compiles against a later one unchanged, so recompile when you upgrade. Binary
compatibility (running code compiled against another release without recompiling) is not promised. Source-breaking
changes of the following only happen in a major version:

| Area | Covered |
|---|---|
| Core API | Public types of `me.ahoo.simba`, `me.ahoo.simba.partition`, `me.ahoo.simba.event`, `me.ahoo.simba.spi` |
| Backends | Public classes of `simba-jdbc`, `simba-redis`, `simba-spring-redis`, `simba-zookeeper` (`JdbcLeaseBackend`, `Dialect`, `RedisLeaseBackend`, `RedisScripting`, `SpringRedisScripting`, `ZookeeperLeaseBackend`, ...) |
| Spring Boot starter | Public types and annotations (`@SimbaScheduled`, `@SimbaPartitioned`, the `*Properties` classes, `SimbaLifecycle`, `MicrometerSimbaEventListener`, `SimbaEndpoint`, `SimbaHealthIndicator`), the `simba.*` properties, bean names and conditions |
| Coroutines | `simba-coroutines` (since 5.1): the functions and types of `me.ahoo.simba.coroutines` (`awaitAcquire`, `awaitTryAcquire`, `withLock`, `awaitRevoked`, `SimbaEventFlow`) |
| Test kits | `simba-testing` (`InMemoryLeaseBackend`, `VirtualClock`, `VirtualScheduler`, `Simulation`) and `simba-tck` (`LeaseBackendSpec`, `FencingSpec`) |
| Observability | Metric names and tag keys, the Actuator endpoint's and the health indicator's JSON fields |
| Storage | The JDBC schema, the Redis keys and messages, the Zookeeper paths |

New fields, tags, enum constants and events may be added in a minor version: read JSON and handle `when` over Simba
enums and events tolerantly. Types marked `@InternalSimbaApi` (the engine, `Simba.Builder.scheduler`,
`Simba.awaitIdle`) and anything `internal` carry no guarantee.

## From 4.x

5.0 is not compatible with 4.x in API, configuration or storage, and needs every node stopped before the switch. Follow
[Migrating from 4.x to 5.0](/guide/migration).

## Within 5.x

Minor and patch releases keep the storage format, so nodes of different 5.x versions can contend for the same names
and you can roll an upgrade node by node. Release notes list every change:
[GitHub Releases](https://github.com/Ahoo-Wang/Simba/releases).

### 5.0 to 5.1 {#from-5-0-to-5-1}

5.1 adds API, changes how the Redis backend times its calls on a Cluster, and keeps 5.0's storage, so nodes of both
versions can run side by side:

- `DistributedLock.acquireAsync(timeout)` and `tryAcquireAsync()` acquire a lock without blocking; see
  [Locks](/guide/locks#without-blocking). They are default methods, so your own `DistributedLock` implementations
  keep compiling unchanged.
- The new optional module `simba-coroutines` adds suspending lock calls, `withLock`, `Term.awaitRevoked()` and
  `SimbaEventFlow`; it depends on `kotlinx-coroutines-core`, which `simba-core` does not.
- `simba-testing`: `InMemoryLeaseBackend.builder()` also sets `sessionBoundGrants`, a grant lasting only what the
  session has left, as on Redis, and `SimulationParams` adds `sessionBoundGrants`, `truncatedGrantShare`,
  `meanInjectionInterval`, `groupPartitions` and `earlyCloseShare` (see [Testing](/guide/testing#deterministic-simulation)).
  The existing constructors and parameters are unchanged.
- Redis with `routeBySlot` (a Cluster), where one call runs a script on the session's slot and then one per lease or
  group slot ([Cluster](/guide/backends#cluster)):
  - Acquire and join fail with `SimbaException` and send nothing to the other slots once the session's remaining
    time has passed since the session call was sent. An acquire whose reply arrives later than that releases the
    terms it began, also on slots that granted, and fails; the engine retries as after any failed call.
  - A lease slot still grants, and extends a lease the holder acquires again, for one full session lease on its own
    clock, as in 5.0.
  - Keep-alive now extends leases and memberships only by what the session has left (its new remaining time minus
    the time since the session call answered), not by a full lease from whenever the script ran: a delayed keep-alive
    no longer keeps a lease of a dead session for up to two leases.
  - No node's `TIME` is compared with another's.
