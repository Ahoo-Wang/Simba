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

`SimbaConfig.builder()` with `leaseDuration` (15s), `renewInterval` (`leaseDuration / 3`), `drift` (0.01), `margin`
(50ms), `retryJitter` (`renewInterval / 2`), `minBackoff` (100ms); unset values keep their defaults, derived ones
follow the values set. `build()` validates `renewInterval × 2 ≤ leaseDuration` and
`leaseDuration × (1 − drift) − margin > renewInterval`; `toBuilder()` derives a variant. There is no public
constructor: `SimbaConfig`, the events and the snapshots are plain classes with value equality (no `copy` or
destructuring), so later versions can add properties compatibly. See
[Timing](/guide/concepts#timing).

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

`fun interface LeaderWork { fun run(term: Term) }` may throw anything; failures (an `Error` included) become
`SimbaEvent.WorkCompleted` and never affect the election, except a `VirtualMachineError` (out of memory, stack
overflow): it ends the term's work, the election steps down for one lease duration, and the error is rethrown to the
work thread. `LeaderTask` (`AutoCloseable`) exposes `election`, `stop(): CompletionStage<Void>` (completes once
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
| `ensureValid(minRemaining)`, `ensureValid()` | Throws `TermExpiredException` unless valid for more than `minRemaining` (`ZERO` without an argument) |
| `revoked: CompletionStage<RevokeReason>` | Completes exactly once, when the term is revoked |

### `RevokeReason`

`STOPPED`, `STEPPED_DOWN`, `SESSION_EXPIRED`, `DEADLINE_PASSED`, `TAKEN_OVER`; see
[Leases, Terms and Tokens](/guide/concepts#leases-terms-and-tokens).

## Locks — `me.ahoo.simba`

| Type | Members |
|---|---|
| `DistributedLock` | `name`; `acquire(timeout): LockTerm` (throws `LockTimeoutException`; declares `InterruptedException`); `tryAcquire(): LockTerm?`; since 5.1 `acquireAsync(timeout): CompletionStage<LockTerm>` and `tryAcquireAsync(): CompletionStage<LockTerm?>` (cancelling abandons the attempt and releases a won term) |
| `LockTerm : Term, AutoCloseable` | `releaseAsync(): CompletionStage<Void>`; `close()` (idempotent) |
| `LockTimeoutException` | `name`, `timeout` |

See [Locks](/guide/locks).

## Partitions — `me.ahoo.simba.partition`

| Type | Members |
|---|---|
| `PartitionGroup` | `name` (a `String`: it names a backend membership group, not a lease, yet follows the lease-name rules), `partitions`; `schedule(schedule, work): GroupTask`; `runWhileLeader(work): GroupTask` |
| `PartitionWork` | `fun run(term: Term, partition: Int)` |
| `GroupTask : AutoCloseable` | `group`; `partitions(): Set<Int>` (this node's partitions in its latest view); `stop()`; `close()` |
| `PartitionAssignment` | `ownerOf(partition, members)`, `partitionsOf(member, partitions, members)`, `weight(partition, member)`: rendezvous hashing (FNV-1a 64 mixed with SplitMix64), identical across versions |

## Events and Snapshot — `me.ahoo.simba.event`

| Type | Members |
|---|---|
| `SimbaEventListener` | `fun onEvent(event: SimbaEvent)`; called in order off the engine loop; exceptions are logged and ignored |
| `SimbaEvent` | `SessionOpened`, `SessionRenewed`, `SessionSuspended`, `SessionExpired` (`reason`: `DEADLINE_PASSED` or `SESSION_EXPIRED`), `SessionLeaseShortened`, `SessionClosed`, `Elected`, `Revoked`, `AcquireFailed`, `WorkCompleted` (the last four carry `kind`) |
| `ElectionKind` | `ELECTION`, `LOCK`, `GROUP`: which API started an election (on `Elected`, `Revoked` and `ElectionSnapshot`) |
| `WorkOutcome` | `COMPLETED`, `FAILED`, `CANCELLED` |
| `SimbaSnapshot` | `session: SessionSnapshot`, `elections: List<ElectionSnapshot>`, `leaseDuration`, `healthy` |
| `SessionSnapshot` | `id`, `status` (`OPENING`, `LIVE`, `SUSPENDED`, `CLOSED`), `validFor`, `notLiveFor` |
| `ElectionSnapshot` | `name`, `status` (`CANDIDATE`, `LEADER`, `COOLDOWN`, `HANDOVER`), `token`, `remaining`, `kind` |

See [Observability](/guide/observability).

## Coroutines — `me.ahoo.simba.coroutines`

Module `simba-coroutines` (since 5.1).

| Type | Contract |
|---|---|
| `DistributedLock.awaitAcquire(timeout): LockTerm` | Suspending `acquireAsync`; cancellation abandons the attempt and releases a won term |
| `DistributedLock.awaitTryAcquire(): LockTerm?` | Suspending `tryAcquireAsync` |
| `DistributedLock.withLock(timeout, block: suspend (LockTerm) -> R): R` | Acquires, runs `block`, releases the term afterwards (also on failure or cancellation) |
| `Term.awaitRevoked(): RevokeReason` | Suspends until the term is revoked; cancelling the wait leaves the term alone |
| `SimbaEventFlow(bufferCapacity = 1024)` | `SimbaEventListener` whose `events: SharedFlow<SimbaEvent>` republishes events; drops the oldest for a slow collector |

See [Locks](/guide/locks#coroutines).

## Backend SPI — `me.ahoo.simba.spi`

```kotlin
interface LeaseBackend : AutoCloseable {
    val capabilities: Capabilities                    // releaseHints, membershipHints
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

- every method makes a bounded number of backend calls, without waiting, scheduling, notifying or keeping local lease
  state;
- every remaining duration is computed with the backend's clock and returned as a duration, never a timestamp;
- a token is greater than zero, strictly increases with every new term of its name and never resets;
- a session expires once it was not kept alive for its lease, even if the transport keeps a connection alive;
- an expired session is never revived: `keepAlive` answers `Expired`, `acquire` throws `SessionExpiredException`;
- `keepAlive` reports every lease the session owns, whatever `held` says, and an owned lease lasts at least as long
  as its session, also on a backend whose leases expire on their own;
- inputs are validated alike, with `IllegalArgumentException`: leases of at least 1 ms, group names under the
  `LeaseName` rules, at most `LeaseBackend.MAX_BATCH` (64) names per `acquire`;
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
