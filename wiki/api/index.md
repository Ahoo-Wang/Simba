---
title: API Reference
description: The public types of Simba 5 and their contracts — Simba, Election, Term, DistributedLock, Schedule, PartitionGroup, SimbaEvent, SimbaSnapshot, the LeaseBackend SPI and the Spring annotations.
---

# API Reference

Public API lives in `me.ahoo.simba` (`simba-core`) and its subpackages `partition`, `event` and `spi`. Times are always
`java.time.Duration`. Types in `me.ahoo.simba.engine` and members marked `@InternalSimbaApi` require an opt-in and are
not public API.

## Client — `me.ahoo.simba`

### `Simba`

One client per process: one backend session, with every election, lock and group attached to it. `AutoCloseable`.

| Member | Description |
|---|---|
| `Simba.builder()` | Builder; see [Without Spring](/guide/configuration#without-spring) |
| `config: SimbaConfig` | The session timing |
| `election(name): Election` | The election of `name`; handles of the same name share one election |
| `lock(name): DistributedLock` | The lock on `name`; a lock and an election of the same name share one lease |
| `group(name, partitions): PartitionGroup` | The partition group `name` with `partitions` partitions |
| `snapshot(): SimbaSnapshot` | The session and every started election, without calling the backend |
| `closeAsync(): CompletionStage<Void>` | Revokes every term, closes the session; completes after the last callback ran |
| `close()` | `closeAsync()` and wait, except inside a callback or leader work |

Names follow the lease name rules: 1–128 characters of `[A-Za-z0-9._:-]`; anything else throws
`IllegalArgumentException`. After `close()`, every method that starts something throws `IllegalStateException`.

### `SimbaConfig`

`data class SimbaConfig(leaseDuration = 15s, renewInterval = leaseDuration / 3, drift = 0.01, margin = 50ms,
retryJitter = renewInterval / 2, minBackoff = 100ms)`. Construction validates `renewInterval × 2 ≤ leaseDuration` and
`leaseDuration × (1 − drift) − margin > renewInterval`. From Java, use `SimbaConfig.builder()` (unset values keep
their defaults, derived ones follow the values set) or the overloaded constructors. See
[Timing](/guide/correctness#timing).

## Elections — `me.ahoo.simba`

### `Election`

| Member | Description |
|---|---|
| `name: LeaseName` | The lease name |
| `start(listener)` | Starts contending; throws `IllegalStateException` when already started |
| `stop(): CompletionStage<Void>` | Stops contending; completes after the last `onRevoked` ran (at once when not started) |
| `stepDown(cooldown): CompletionStage<Void>` | Gives up the current term and does not contend for `cooldown`; stays started |
| `runWhileLeader(executor?, work): LeaderTask` | Starts the election and runs `work` once per term |
| `schedule(schedule, work): LeaderTask` | Starts the election and runs `work` on `schedule` while leader |

Contract: every `onElected(term)` is followed by exactly one `onRevoked(term, reason)`; callbacks of one election run
one at a time, in order; the engine never waits for a callback except through the futures of `stop()` and
`stepDown()`; after `stop()` completed, no more callbacks.

### `LeadershipListener`

```kotlin
interface LeadershipListener {
    fun onElected(term: Term)
    fun onRevoked(term: Term, reason: RevokeReason)
}
```

### `LeaderWork` and `LeaderTask`

`fun interface LeaderWork { fun run(term: Term) }` may throw; failures become `SimbaEvent.WorkCompleted` and never
affect the election. `LeaderTask` (`AutoCloseable`) exposes `election`, `stop(): CompletionStage<Void>` (completes once
the work exited or timed out and the lease is released) and `close()`. See [Leader Work](/guide/leader-work).

### `Schedule`

`Schedule.fixedDelay(delay, initialDelay = 0)`, `Schedule.fixedRate(period, initialDelay = 0)`,
`withInitialDelay(initialDelay)`; `mode`, `period`, `initialDelay`. The period must be positive. A fixed-rate run that
overruns skips the missed starts.

## Terms — `me.ahoo.simba`

### `Term`

| Member | Description |
|---|---|
| `name: LeaseName` | The lease name |
| `token: Long` | Fencing token: `> 0`, constant within the term, strictly increasing across terms of the name |
| `isValid: Boolean` | Not revoked and the session's local deadline has not passed |
| `remaining(): Duration` | Time left before the local deadline; `ZERO` once the term ended |
| `ensureValid(minRemaining)`, `ensureValid()` | Throws `LeaseExpiredException` unless valid for more than `minRemaining` (`ZERO` without an argument) |
| `revoked: CompletionStage<RevokeReason>` | Completes exactly once, when the term is revoked |

### `RevokeReason`

`STOPPED`, `STEPPED_DOWN`, `SESSION_EXPIRED`, `DEADLINE_PASSED`, `TAKEN_OVER`; see
[Leases, Terms and Tokens](/guide/correctness#leases-terms-and-tokens).

## Locks — `me.ahoo.simba`

| Type | Members |
|---|---|
| `DistributedLock` | `name`; `acquire(timeout): LockTerm` (throws `LockTimeoutException`); `tryAcquire(): LockTerm?` |
| `LockTerm : Term, AutoCloseable` | `releaseAsync(): CompletionStage<Void>`; `close()` (idempotent) |
| `LockTimeoutException` | `name`, `timeout` |

See [Locks](/guide/locks).

## Partitions — `me.ahoo.simba.partition`

| Type | Members |
|---|---|
| `PartitionGroup` | `name`, `partitions`; `schedule(schedule, work): GroupTask`; `runWhileLeader(work): GroupTask` |
| `PartitionWork` | `fun run(term: Term, partition: Int)` |
| `GroupTask : AutoCloseable` | `group`; `partitions(): Set<Int>` (this node's partitions in its latest view); `stop()`; `close()` |
| `PartitionAssignment` | `ownerOf(partition, members)`, `partitionsOf(member, partitions, members)`, `weight(partition, member)`: rendezvous hashing (FNV-1a 64 mixed with SplitMix64), identical across versions |

## Events and Snapshot — `me.ahoo.simba.event`

| Type | Members |
|---|---|
| `SimbaEventListener` | `fun onEvent(event: SimbaEvent)`; exceptions are logged and ignored |
| `SimbaEvent` | `SessionOpened`, `SessionRenewed`, `SessionSuspended`, `SessionExpired`, `SessionClosed`, `Elected`, `Revoked`, `AcquireFailed`, `WorkCompleted` |
| `ElectionKind` | `ELECTION`, `LOCK`, `GROUP`: which API started an election (on `Elected`, `Revoked` and `ElectionSnapshot`) |
| `WorkOutcome` | `COMPLETED`, `FAILED`, `CANCELLED` |
| `SimbaSnapshot` | `session: SessionSnapshot`, `elections: List<ElectionSnapshot>`, `leaseDuration`, `healthy` |
| `SessionSnapshot` | `id`, `status` (`OPENING`, `LIVE`, `SUSPENDED`, `CLOSED`), `validFor`, `notLiveFor` |
| `ElectionSnapshot` | `name`, `status` (`CANDIDATE`, `LEADER`, `COOLDOWN`, `HANDOVER`), `token`, `remaining`, `kind` |

See [Observability](/guide/observability).

## Backend SPI — `me.ahoo.simba.spi`

```kotlin
interface LeaseBackend : AutoCloseable {
    val capabilities: Capabilities                    // releaseHints, membershipHints, maxBatch
    fun openSession(lease: Duration, metadata: Map<String, String> = emptyMap()): SessionGrant
    fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult   // Live(remaining, owned) | Expired
    fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult>      // Granted(token) | HeldBy(session, token)
    fun release(session: SessionId, leases: Map<LeaseName, Long>)
    fun closeSession(session: SessionId)
    fun join(session: SessionId, group: String)
    fun leave(session: SessionId, group: String)
    fun members(group: String): List<SessionId>
    fun subscribe(listener: BackendHintListener): AutoCloseable   // optional Released / MembersChanged hints
}
```

Rules every implementation follows (checked by [`LeaseBackendSpec`](/guide/testing#backend-tck)):

- every method is one backend round trip (or one per slot), with no scheduling, no notification and no local state;
- every remaining duration is computed with the backend's clock and returned as a duration, never a timestamp;
- a token is greater than zero, strictly increases with every new term of its name and never resets;
- a session expires once it was not kept alive for its lease, even if the transport keeps a connection alive;
- an expired session is never revived: `keepAlive` answers `Expired`, `acquire` throws `SessionExpiredException`;
- `release` frees only leases the session still owns with exactly the given token, so a stale release is a no-op;
- hints only accelerate; losing one never affects correctness.

Value types: `LeaseName` (1–128 characters of `[A-Za-z0-9._:-]`, not `.` or `..`), `SessionId` (never reused) —
both plain immutable classes with value equality, usable from Java — and `Ticker` (monotonic nanoseconds). Implementations: `JdbcLeaseBackend`, `RedisLeaseBackend`, `ZookeeperLeaseBackend`,
`InMemoryLeaseBackend`; see [Backends](/guide/backends).

## Spring Boot — `me.ahoo.simba.spring.boot.starter`

| Type | Description |
|---|---|
| `@SimbaScheduled(name, fixedDelay, fixedRate, initialDelay)` | Runs a method `()` or `(Term)` on `election(name).schedule(...)` |
| `@SimbaPartitioned(group, partitions, fixedDelay, fixedRate, initialDelay)` | Runs a method `(Term, Int)` on `group(group, partitions).schedule(...)` |
| `SimbaEndpoint` | Actuator endpoint `simba` |
| `SimbaHealthIndicator` | Health indicator `simba` |
| `MicrometerSimbaEventListener` | Micrometer meters from `SimbaEvent`s |

See [Spring Boot](/guide/configuration) and [Leader Work](/guide/leader-work#spring-annotations).
