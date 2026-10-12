# ADR 0008: Relinquishing Ownership and Graceful Handover

- Status: Accepted
- Date: 2026-10-12

## Context

Today a node gives up a mutex only by dying, by losing its lease, or by `stop()`, which also ends its contention.
Operations need a third way: during a rolling deploy, a drain, or rebalancing, step back from leadership while staying
a candidate.

Leader-only work also hands over in the wrong order. `SimbaScheduler.stop()` (and `AbstractScheduler.stop()`) stops the
contend service first, which releases the lease remotely, and only then cancels the work through `onReleased`. A
release that makes another node leader immediately (Redis broadcasts it, Zookeeper deletes the latch node) lets the new
leader start while the old leader's run is still finishing. Fencing tokens bound the damage, but the overlap is
avoidable.

From first principles, a clean handover is: stop starting work, let running work end, then release the lease, and
only then may a successor start. The old owner's wait is bounded by its lease: once that ends, a successor may take
over anyway.

## Decision

### 1. `MutexContendService.relinquish(cooldown)` (simba-core, additive)

Releases ownership if held and stays out of contention for `cooldown`, then contends again. The service stays
`RUNNING`; a non-owner simply pauses its contention. `cooldown` must not be negative. A later call replaces the
cooldown. `stop()` ends it; `start()` begins without one. Like `stop()`, it returns after the release was
delivered (when called from a callback, the release runs right after that callback). The default interface body throws
`UnsupportedOperationException`, so custom implementations keep compiling.

- **JDBC and Redis (`LeaseContendService`)**: under the lifecycle lock, the held lease is dropped, released in the
  store, and `NONE` is notified. Every contention trigger is deferred to the cooldown end, including the reschedule
  after an in-flight call and Redis release broadcasts. A contention already in flight that wins the lease during the
  cooldown is released immediately and never reported as ownership.
- **Zookeeper**: the latch is closed with `NOTIFY_LEADER`. The next candidate leads at once. A new latch starts after the
  cooldown through `CompletableFuture.delayedExecutor`, so no executor is added to the factory. The restart is
  dropped if the lifecycle changed in the meantime.

Successors take over as fast as the backend allows: on Redis, other contenders are woken by the release broadcast; on
Zookeeper, the next latch node leads at once. JDBC has no wake-up signal, so other contenders still acquire at their
next scheduled attempt (about the old lease end). Relinquishing on JDBC frees the mutex but does not shorten
failover.

### 2. Work stops before the lease is released (scheduling)

`SimbaScheduler` and `AbstractScheduler` gain `relinquish(cooldown)`, and their `stop()` changes order:

1. Cancel the scheduled work, interrupting a running run.
2. Wait until the running run returns. The wait is bounded by `leaseRemaining`, at most 30 seconds when the lease is
   unbounded (Zookeeper).
3. Release, through `MutexContendService.relinquish` or `stop`.

If the run outlives the wait, the release proceeds anyway and a warning is logged; the fencing token guards the tail.
`@SimbaScheduled` methods inherit this through their `SimbaScheduler`.

### Not now

- **`tryAcquire()`**: the first contention is asynchronous, so an immediate answer would almost always be "no".
  `acquire(timeout)` already expresses "try briefly".
- **Coroutine API**: needs a new module and a `kotlinx-coroutines` dependency; deferred until there is demand.
- **Relinquishing through the Actuator endpoint**: remote eviction stays out, as decided in ADR 0006.

## Consequences

- Additive: released as 4.5.0. The scheduler stop order is a behavior change that only removes an overlap.
- The TCK gains a `relinquish` case: another contender takes over after a relinquish, and the relinquishing contender
  contends again after the cooldown.
