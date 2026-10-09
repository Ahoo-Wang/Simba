# ADR 0001: Shared Lease Contention Engine

- Status: Proposed
- Date: 2026-10-09

## Context

Simba's polling backends (JDBC, Redis) each re-implement the same contention loop: lifecycle lock,
generation tracking, scheduling, renew-failure revocation, and compensation for late acquisitions. Together with
the generation and notify lock in `AbstractMutexRetrievalService`, that is three locks and two generation
counters per service, which makes concurrency behavior hard to reason about and expensive to extend.

The architecture review also found:

1. Local ownership can outlive the lease: `isOwner` ignores TTL, and a hung backend call (no JDBC query
   timeout) keeps the contender believing it owns the mutex past `ttlAt`.
2. No fencing token is exposed, so a stale owner cannot be rejected downstream.
3. The Redis contender queue never expires dead contenders, and a release wakes only one of them.
4. With several backends on the classpath, the starter's factory selection depends on auto-configuration order,
   because the Zookeeper `@Bean` returns a concrete type while the others return the interface.
5. Resource models diverge: JDBC uses one thread per mutex, while Redis shares one scheduler thread that also
   performs blocking I/O. Callbacks default to `ForkJoinPool.commonPool()`.
6. Duration validation, `MutexOwner` time sourcing (inheritance overriding `currentAt`), and release
   notifications are duplicated or mixed across classes. `status` has a public setter, `close()` is not
   idempotent, and `MutexRetrievalServiceFactory` has no implementation.

## Decision

Move the polling contention loop into `simba-core` and reduce polling backends to a storage SPI:

```kotlin
interface MutexLeaseStore {
    fun contend(mutex: String, contenderId: String, renew: Boolean, config: LeaseConfig): MutexOwner
    fun release(mutex: String, contenderId: String): Boolean
}
```

- `LeaseConfig(ttl, transition, initialDelay)` is a validated value object that replaces per-backend validation.
- A `LeaseContendService` owns the single lifecycle lock and generation, scheduling, failure revocation,
  late-acquisition compensation, a local lease watchdog based on monotonic time, and a `wakeUp()` hook for event
  sources such as Redis pub/sub.
- A factory-owned scheduler triggers contention for all mutexes; blocking I/O runs on a bounded executor.
- Zookeeper stays event-driven on `LeaderLatch` and does not use the engine.

## Rollout

| Phase | Scope | Compatibility |
|---|---|---|
| 1 (3.x) | Lease watchdog and JDBC query timeout; consistent starter bean types; extract the engine; encapsulate `status`; idempotent `close()`; shared scheduler | No public API break |
| 2 (3.x) | Optional fencing token; Redis queue expiry and structured Lua results; configurable callback executor | Additive; Redis upgrade order must be documented |
| 3 (4.0) | `MutexOwner` as an immutable value with an injected clock; remove unused retrieval factory; drop cosid/Guava from core; explicit `simba.backend` selection; JDBC dialect SPI | Breaking |

## Consequences

- Concurrency reasoning concentrates in one core class, covered by the shared TCK.
- JDBC and Redis services shrink to thin adapters; a new polling backend implements two methods.
- `AGENTS.md` invariants (Lifecycle, Threading, Time) must be updated in the same PR that lands each phase.
