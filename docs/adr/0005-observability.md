# ADR 0005: Contention Observability

- Status: Accepted
- Date: 2026-10-10

## Context

Operators could not answer "is this node the leader", "how often does leadership move", "is the backend failing"
or "was ownership revoked by the lease watchdog" without reading logs. A watchdog revocation means the node
briefly acted without a valid lease, yet it left only a WARN line.

## Decision

1. **`ContendObserver` (simba-core, no dependency)**: `onContend(mutex, renew, durationNanos, outcome)`,
   `onAcquired(mutex)`, `onReleased(mutex)`, `onLeaseExpired(mutex)`, `onWork(mutex, durationNanos, outcome)`, all
   default no-ops; `ContendObserver.NOOP` is the default everywhere.
2. **Wiring**: `AbstractMutexContendService` carries the observer and reports ownership changes in notification
   order (all backends); `LeaseContendService` reports contention round trips and watchdog revocations (JDBC,
   Redis); `SimbaScheduler` / `AbstractScheduler` report work runs to the observer of their contend service.
   Backend factories and services take the observer as a trailing optional parameter (`@JvmOverloads`), so existing
   source and binaries keep working. Observer exceptions are logged and never affect contention.
3. **Micrometer (starter)**: an optional `micrometer-core` dependency; with a `MeterRegistry` bean the starter
   passes a Micrometer observer to the backend factories (`simba.metrics.enabled`). Meters are tagged by mutex only
   — never by contender id — to keep cardinality bounded.
4. **Not now**: an actuator endpoint listing owners, until there is demand (added later by [ADR 0006](0006-actuator-endpoint.md)).

## Consequences

- Additive: released as 4.2.0.
- Zookeeper reports ownership and work events only: Curator's `LeaderLatch` owns its contention.
- The TCK `observer()` case runs on backends whose test provides `createObservedFactory`.
