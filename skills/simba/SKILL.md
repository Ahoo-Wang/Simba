---
name: simba
description: Guide for using the Simba distributed mutex and leader-election library in JVM projects. Use when creating distributed locks, implementing leader-only work, configuring Simba backends (JDBC/MySQL, Redis, Zookeeper), writing MutexContender or AbstractScheduler subclasses, using SimbaLocker, integrating Simba with Spring Boot, choosing backends, or tuning TTL/transition settings. For test-focused work, use the simba-testing skill as well.
---

# Simba — Distributed Mutex Library

Simba provides distributed mutex (leader election) for JVM applications with three pluggable backends: JDBC/MySQL, Redis (Spring Data Redis), and Zookeeper (Apache Curator).

## How to Use This Skill

Start by identifying four things:
1. Which backend the project already runs: Redis, JDBC/MySQL, or Zookeeper.
2. Which usage pattern fits the job: `MutexContender`, `SimbaLocker`, or `AbstractScheduler`.
3. Who owns lifecycle: explicit `start()`/`stop()`, Kotlin `.use {}`, Java try-with-resources, or Spring `SmartLifecycle`.
4. Whether the task is usage/configuration work or test work. For test-heavy tasks, also use `simba-testing`.

Read `references/backend-internals.md` only when debugging backend behavior, explaining Lua/SQL/Curator internals, or tuning failure and handoff semantics.

## Backend Selection

Help the developer pick the right backend based on their infrastructure:

| Backend | Module | Best when |
|---------|--------|-----------|
| **Redis** | `simba-spring-redis` | Already using Redis. Best performance — Lua scripts + Pub/Sub for near-real-time notification. Recommended default. |
| **JDBC/MySQL** | `simba-jdbc` | No Redis available. Uses polling with optimistic locking. Requires MySQL init script. |
| **Zookeeper** | `simba-zookeeper` | Already using Zookeeper/Curator. Delegates to Curator's `LeaderLatch`. Simplest backend. |

Decision heuristic: If the project has Redis, use Redis. If it has Zookeeper/Curator, use Zookeeper. If neither, use JDBC. If multiple are available, prefer Redis for its Pub/Sub-based notification (lower latency than polling).

## Gradle Dependencies

The project uses Gradle feature capabilities in `simba-spring-boot-starter` so consumers only pull the backend they need.

For **Redis**:
```kotlin
implementation("me.ahoo.simba:simba-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.simba:spring-redis-support")
    }
}
```

For **JDBC**:
```kotlin
implementation("me.ahoo.simba:simba-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.simba:jdbc-support")
    }
}
```

For **Zookeeper**:
```kotlin
implementation("me.ahoo.simba:simba-spring-boot-starter") {
    capabilities {
        requireCapability("me.ahoo.simba:zookeeper-support")
    }
}
```

For non-Spring projects, depend on the backend module directly:
```kotlin
implementation("me.ahoo.simba:simba-spring-redis")
// or
implementation("me.ahoo.simba:simba-jdbc")
// or
implementation("me.ahoo.simba:simba-zookeeper")
```

## Three Usage Patterns

Simba offers three abstractions, from low-level to high-level. Guide the developer to the simplest one that fits their use case.

### Pattern 1: MutexContender (callback-based leader election)

Use when: the application needs to react to leadership changes (start/stop work when gaining/losing leadership).

The developer creates a class extending `AbstractMutexContender` and overrides `onAcquired` / `onReleased`:

```kotlin
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexState

class MyContender : AbstractMutexContender(mutex = "my-task") {
    override fun onAcquired(mutexState: MutexState) {
        // This instance is now the leader — start doing work
    }

    override fun onReleased(mutexState: MutexState) {
        // Lost leadership — stop doing work
    }
}
```

Then create and start the service:
```kotlin
val contendService = mutexContendServiceFactory.createMutexContendService(MyContender())
contendService.start()
// ... later
contendService.stop()
```

Key points to explain:
- `mutex` is the logical lock name. All contenders for the same mutex compete for one lock.
- `contenderId` defaults to `"{counter}:{pid}@{hostAddress}"` via `ContenderIdGenerator.HOST`. Override to use `ContenderIdGenerator.UUID` or a custom ID.
- `onAcquired` / `onReleased` are called asynchronously on the `handleExecutor`. Don't block these callbacks.
- The service must be started with `start()` and stopped with `stop()` when done.

### Pattern 2: SimbaLocker (RAII-style blocking lock)

Use when: the developer needs a simple "acquire lock, do work, release" pattern — especially inside `@Scheduled` methods or one-off tasks.

```kotlin
import me.ahoo.simba.locker.SimbaLocker

SimbaLocker("my-lock", mutexContendServiceFactory).use { locker ->
    locker.acquire(Duration.ofSeconds(5))  // throws TimeoutException if lock not acquired
    // do work while holding the lock
}
// close() is called automatically by use{}, releasing the lock
```

Or with explicit try/finally:
```kotlin
import me.ahoo.simba.locker.SimbaLocker

val locker = SimbaLocker("my-lock", mutexContendServiceFactory)
try {
    locker.acquire(Duration.ofSeconds(5))
    // do work
} finally {
    locker.close()
}
```

Key points:
- `acquire(timeout)` blocks the current thread until the lock is acquired or timeout expires (throws `TimeoutException`).
- `acquire()` blocks indefinitely.
- `close()` releases the lock. Always use try-with-resources / `.use {}` to guarantee release.
- Internally creates a `MutexContendService`; the thread parks until `onAcquired` fires.

### Pattern 3: AbstractScheduler (leader-only periodic task)

Use when: the application needs a periodic task that should run on exactly one instance at a time.

```kotlin
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig

class MyScheduler(mutexContendServiceFactory: MutexContendServiceFactory) :
    AbstractScheduler(mutex = "my-scheduled-task", mutexContendServiceFactory) {

    override val config = ScheduleConfig.delay(
        initialDelay = Duration.ZERO,
        period = Duration.ofSeconds(30)
    )
    override val worker: String = "my-scheduler"

    override fun work() {
        // This runs only on the leader instance, at the configured period
    }
}
```

Then start/stop it:
```kotlin
val scheduler = MyScheduler(mutexContendServiceFactory)
scheduler.start()
// ... later
scheduler.stop()
```

For Spring Boot, implement `SmartLifecycle` to auto-start/stop:
```kotlin
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig

@Service
class MyScheduler(mutexContendServiceFactory: MutexContendServiceFactory) :
    AbstractScheduler(mutex = "my-task", mutexContendServiceFactory),
    SmartLifecycle {

    override val config = ScheduleConfig.delay(Duration.ZERO, Duration.ofSeconds(30))
    override val worker = "my-scheduler"
    override fun work() { /* ... */ }
}
```

Key points:
- `ScheduleConfig.delay(initial, period)` = fixed-delay (waits `period` after each execution ends).
- `ScheduleConfig.rate(initial, period)` = fixed-rate (fires every `period` regardless of execution duration).
- `work()` runs on a `ScheduledThreadPoolExecutor` — it should be reasonably fast or handle its own threading.
- Leadership changes automatically cancel and reschedule the work.

## Spring Boot Auto-Configuration

When `simba-spring-boot-starter` is on the classpath, a `MutexContendServiceFactory` bean is auto-configured based on which backend module is present.

Configuration properties:

```yaml
simba:
  enabled: true                    # master switch (default: true)
  redis:
    enabled: true                  # enable Redis backend (default: true)
    ttl: 10s                       # lock TTL — owner must renew before this (default: 10s)
    transition: 6s                 # grace period after TTL — owner gets priority to renew (default: 6s)
  jdbc:
    enabled: true                  # enable JDBC backend (default: true)
    initial-delay: 0s              # delay before first contention attempt (default: 0s)
    ttl: 10s                       # lock TTL (default: 10s)
    transition: 6s                 # transition/grace period (default: 6s)
  zookeeper:
    enabled: true                  # enable Zookeeper backend (default: true)
```

Prefer one backend capability and one enabled backend per application. If multiple backend modules are on the classpath and enabled, Spring can expose multiple `MutexContendServiceFactory` beans; use `@Primary` or `@Qualifier` only when that ambiguity is intentional.

### TTL and Transition Tuning

The dual-timestamp model is central to Simba's design:

- **TTL (soft expiry)**: The lock's nominal expiration. The current owner should renew before TTL expires. Shorter TTL = faster failure detection but more renewal overhead.
- **Transition (hard expiry)**: A grace period after TTL. During the transition window, only the current owner can renew. After transition, any contender can compete. This prevents unnecessary leadership churn when the owner is briefly slow.

Guidelines:
- `transition` should be less than `ttl` (typically 50-70% of TTL).
- For Redis backend: TTL/transition are set on the Redis key via `PX` (milliseconds).
- For JDBC backend: stored as `ttl_at` and `transition_at` columns in the `simba_mutex` table.
- For Zookeeper: TTL/transition are not used — Curator's `LeaderLatch` handles lifecycle.

## JDBC Backend Setup

The JDBC backend requires a `simba_mutex` table. Provide the init script at:
`simba-jdbc/src/init-script/init-simba-mysql.sql`

Requires a `DataSource` bean in the Spring context.

## Testing Pointer

For tests, use the `simba-testing` skill. In short:
- Application code usually mocks `MutexContendServiceFactory` and captures the created `MutexContender`.
- Backend implementations should extend `simba-test`'s `MutexContendServiceSpec`.
- New Kotlin assertions should use `import me.ahoo.test.asserts.assert` and the `.assert()` extension.

## Common Pitfalls

1. **Forgetting to stop the service**: Always call `stop()` or `close()` — otherwise the contender keeps polling/subscribing and may hold the lock.
2. **Blocking callbacks**: `onAcquired`/`onReleased` run on a shared executor. Long-running work in these callbacks will delay other contenders' notifications.
3. **Multiple backends enabled**: If both Redis and JDBC are on the classpath without explicit disambiguation, Spring will fail to autowire `MutexContendServiceFactory`.
4. **Clock skew with JDBC**: The JDBC backend uses DB server time (`currentDbAt`) to avoid clock skew across application nodes. Ensure all nodes point to the same DB.
5. **Redis key expiration**: If the Redis key expires (process crash), the transition period gives the old owner a chance to reclaim. After transition, the next contender in the sorted-set queue is notified via Pub/Sub.
6. **Zookeeper path conflicts**: The Zookeeper backend creates paths at `/simba/{mutex}`. Don't use the same mutex name for unrelated locks.
