---
title: API Reference
description: The public types of Simba — contend services, contenders, owners, locker, schedulers, observers and the backend SPI.
---

# API Reference

All types are in `simba-core` unless noted. Package `me.ahoo.simba` is abbreviated as `simba`.

## Contending

### `MutexContendServiceFactory` — `simba.core`

```kotlin
interface MutexContendServiceFactory {
    fun createMutexContendService(mutexContender: MutexContender): MutexContendService
}
```

Entry point of every API. Implementations: `JdbcMutexContendServiceFactory`, `SpringRedisMutexContendServiceFactory`,
`ZookeeperMutexContendServiceFactory` (see [Configuration](/guide/configuration#without-spring)). The JDBC and Redis
factories are `AutoCloseable` and own their executors.

### `MutexContender` / `AbstractMutexContender` — `simba.core`

| Member | Description |
|---|---|
| `mutex: String` | Name of the mutex. Contenders with the same name compete. |
| `contenderId: String` | Unique id of this contender. Default: `ContenderIdGenerator.HOST` (`counter:pid@host`). |
| `onAcquired(mutexState)` | This contender became the owner. |
| `onReleased(mutexState)` | This contender stopped being the owner (released, lost, revoked or stopped). |

Extend `AbstractMutexContender(mutex, contenderId = ...)`; it validates both values and logs the callbacks. Callbacks
run on the handle executor, in order and never concurrently for one contender.

### `MutexContendService` — `simba.core`

Created by a factory for one contender; `AutoCloseable`.

| Member | Description |
|---|---|
| `start()` / `stop()` | Start or stop contending. `stop()` throws unless `RUNNING`, delivers `onReleased` if owner, and returns after it ran. |
| `close()` | Idempotent `stop()`. |
| `status` | `INITIAL → STARTING → RUNNING → STOPPING → INITIAL`. A failed `start()` returns to `INITIAL`; services are restartable. |
| `running` | `status` is `STARTING` or `RUNNING`. |
| `isOwner` | This contender owns the mutex (local, weakly consistent view). |
| `isInTtl` | Owner and before `ttlAt`. |
| `fencingToken` | Token of the current term while owner, else `0`. |
| `leaseRemaining` | How long local ownership stays valid: `Duration.ZERO` when not owner, `UNBOUNDED_LEASE` on Zookeeper. Lease backends measure it to the lease watchdog's deadline. |
| `ensureLease(minRemaining)` | Throws `LeaseExpiredException` unless `leaseRemaining` exceeds `minRemaining` (default zero). |
| `relinquish(cooldown)` | Releases ownership if held and stays out of contention for `cooldown`, then contends again; stays `RUNNING`. Throws unless running. |
| `afterOwner` / `beforeOwner` / `mutexState` | Last observed owner and the previous one. |
| `hasOwner()` | Someone holds a lease that has not ended. |

### `MutexState` and `MutexOwner` — `simba.core`

`MutexState(before, after)` is passed to callbacks; `isAcquired(id)` / `isReleased(id)` tell which transition happened.

`MutexOwner` is an immutable value describing a lease:

| Field | Description |
|---|---|
| `ownerId` | Owner's contender id; `""` for `MutexOwner.NONE`. |
| `acquiredAt`, `ttlAt`, `transitionAt` | Epoch millis on the backend clock. `Long.MAX_VALUE` for Zookeeper (no lease). |
| `fencingToken` | Token of the term, `0` when none. |
| `observedAt`, `currentAt` | Backend time when observed, and that time advanced by the local monotonic clock. |

Equality ignores `observedAt`.

## Locking — `simba.locker`

```kotlin
interface Locker : AutoCloseable {
    fun acquire()                      // waits until owner
    fun acquire(timeout: Duration)     // throws TimeoutException
    val fencingToken: Long
    val leaseRemaining: Duration        // Duration.ZERO when not held
    fun ensureLease(minRemaining: Duration = Duration.ZERO)  // throws LeaseExpiredException
}
class SimbaLocker(mutex: String, contendServiceFactory: MutexContendServiceFactory) : Locker
```

`close()` stops contending and releases the mutex. A locker is owned by one thread at a time (`acquire` from a second
thread throws `IllegalMonitorStateException`); interruption does not cancel `acquire()`, and the interrupt flag is
restored. A failed `acquire` closes the locker.

## Scheduling — `simba.schedule`

### `SimbaScheduler`

```kotlin
class SimbaScheduler(
    mutex: String,
    contendServiceFactory: MutexContendServiceFactory,
    config: ScheduleConfig,
    worker: String = mutex,          // thread name prefix
    work: ScheduledWork              // fun interface: work(context: ScheduleContext)
) : AutoCloseable
```

Members: `start()`, `stop()`, idempotent `close()`, `relinquish(cooldown)`, `running`, `isLeader`, `fencingToken`. When
the node acquires the mutex, a single-thread executor is created and runs the work; on release the run is cancelled
with interruption. A node that never leads holds no work thread. `stop()` and `relinquish(cooldown)` first cancel the
work and wait for a running run (at most the remaining lease, 30 s when unbounded), then release the mutex.

`ScheduleConfig.delay(initialDelay, period)` runs with a fixed delay between runs; `ScheduleConfig.rate(initialDelay,
period)` at a fixed rate. `ScheduleContext` exposes `mutex`, `fencingToken`, `leaseRemaining` and `ensureLease(minRemaining)`. A failed run is logged and the next one
still runs.

`AbstractScheduler(mutex, factory)` offers the same semantics by subclassing (`config`, `worker`, `work()`,
protected `fencingToken`, `leaseRemaining`, `ensureLease()`, public `relinquish(cooldown)`); prefer `SimbaScheduler`.

### `SimbaPartitionedScheduler` — `simba.partition`

```kotlin
class SimbaPartitionedScheduler(
    group: String,
    partitions: Int,
    contendServiceFactory: MutexContendServiceFactory,
    memberRegistry: MemberRegistry,
    config: ScheduleConfig,
    membershipTtl: Duration = 15s,
    memberId: String = generated,
    worker: String = group,
    work: PartitionedWork            // fun interface: work(context: PartitionContext)
) : AutoCloseable
```

Members: `start()` (joins the group, failing when the registry is unreachable), `stop()` (stops every partition, then
leaves), idempotent `close()`, `running`, `members`, `assignedPartitions`, `ownedPartitions`. The node heartbeats every
`membershipTtl / 3` and runs a `SimbaScheduler` on mutex `{group}.{partition}` for each partition that
`PartitionAssignment.ownerOf(partition, members)` gives it. `PartitionContext` extends `ScheduleContext` with
`group`, `partition` and `partitions`. When the registry is unreachable, the node keeps its current partitions.

`MemberRegistry` (`heartbeat(group, memberId, ttl)`, `leave`, `members`) is implemented by `JdbcMemberRegistry`,
`SpringRedisMemberRegistry` and `ZookeeperMemberRegistry`; `simba-test` provides `MemberRegistrySpec`.

### `@SimbaScheduled` — `simba-spring-boot-starter`, `simba.spring.boot.starter.scheduling`

| Attribute | Description |
|---|---|
| `mutex` | Mutex whose leader runs the method; unique per application. |
| `fixedDelay` / `fixedRate` | Exactly one. Spring Boot duration, placeholders allowed. |
| `initialDelay` | Delay after becoming leader. Default `0s`. |
| `worker` | Thread name prefix. Default: `mutex`. |

The method takes no parameter or one `ScheduleContext`. The starter registers a `SimbaScheduler` per method, starts it
after context refresh and stops it on shutdown; `SimbaScheduler` beans are managed the same way. Invalid declarations,
duplicate mutexes, or a missing `MutexContendServiceFactory` fail startup.

## Observing — `simba.core`

`ContendObserver` receives events from a contend service; every method defaults to a no-op, and exceptions are logged
and ignored. Pass it to a factory (or declare it as a bean with the starter).

| Method | When | Backends |
|---|---|---|
| `onStarted(service)` / `onStopped(service)` | `start()` succeeded / `stop()` completed | All |
| `onContend(mutex, renew, durationNanos, outcome)` | A backend round trip ended: `OWNER`, `OTHER` or `FAILED` | JDBC, Redis |
| `onAcquired(mutex)` / `onReleased(mutex)` | This contender gained / lost ownership | All |
| `onLeaseExpired(mutex)` | The lease watchdog revoked ownership; `onReleased` follows | JDBC, Redis |
| `onWork(mutex, durationNanos, outcome)` | A scheduler run ended: `SUCCESS`, `FAILED` or `INTERRUPTED` | All |

See [Observability](/guide/observability) for the Micrometer metrics and Actuator endpoint built on it.

## Backend SPI — `simba.core`

To add a polling backend, implement one atomic storage call and reuse the engine:

```kotlin
interface MutexLeaseStore {
    /** Acquire, or renew when [renew] is true; return the owner the backend observed afterwards. */
    fun contend(mutex: String, contenderId: String, renew: Boolean, config: LeaseConfig): MutexOwner
    /** Release when held by [contenderId]; true when this call released it. */
    fun release(mutex: String, contenderId: String): Boolean
}
```

`LeaseContendService(contender, handleExecutor, leaseStore, leaseConfig, scheduler, ioExecutor, observer)` runs the
contention loop, lease watchdog and lifecycle for it. `LeaseConfig(ttl, transition, initialDelay)` validates the
durations. `ContendExecutors` creates the default scheduler, I/O and callback executors. See
[Contributing](/contributing/#adding-a-backend).
