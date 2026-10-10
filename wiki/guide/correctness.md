---
title: Correctness
description: The lease model behind Simba, how to choose ttl and transition, how to use fencing tokens, and how each failure plays out.
---

# Correctness

A distributed lock cannot know that its holder is still alive; it can only *lease* ownership for a bounded time.
Everything Simba guarantees follows from that. This page explains the lease, the timing parameters, and what your
code has to do when overlapping work must never happen.

## The Lease

An owner holds a lease with two deadlines, both measured on the backend's clock:

- `ttlAt` — the owner renews at this point.
- `transitionAt = ttlAt + transition` — the lease ends. Between the two, only the current owner may renew; after
  `transitionAt` anyone may acquire.

```mermaid
graph LR
    A["acquiredAt"] -->|"ttl"| B["ttlAt<br>owner renews"]
    B -->|"transition (owner only)"| C["transitionAt<br>lease ends"]
    C -->|"+ jitter"| D["contenders acquire"]
    style A fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style B fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style C fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style D fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

- **Owners** attempt renewal at `ttlAt`. A successful renewal starts a new lease; the fencing token stays the same.
- **Contenders** retry at `transitionAt` plus a random jitter in `[-200, 1000)` ms (`[0, 1000)` when `transition` is
  zero), so they do not all hit the backend at once. On Redis, a release broadcast makes them retry immediately.
- **Local lease guard.** Each successful acquire or renew arms a watchdog for the end of the lease, measured with the
  local monotonic clock *from when the request was sent*. If no renewal succeeds by then, including when the backend
  call hangs, the node revokes its own ownership and calls `onReleased`. Because the watchdog starts before the
  backend granted the lease, local ownership ends no later than the backend lease.
- **Failed renewal.** While the lease is still valid, the owner keeps ownership and retries with a halving back-off
  (at least 100 ms). Once it has ended, ownership is revoked and the node contends again after `ttl`.

Zookeeper has no lease: leadership lasts until the latch closes or the session expires (see
[Backends](/guide/backends#zookeeper)).

## Choosing `ttl` and `transition`

Both are lease lengths, unrelated to how long your work runs: renewal happens in the background while work runs.

- **`ttl + transition` is the failover time** after a crash or partition. Shorter means faster takeover.
- **`transition` is the renewal slack.** A renewal that is late by less than `transition` (slow backend, GC pause,
  busy scheduler) keeps ownership. With `transition = 0`, any delay at `ttlAt` lets another node take over, so
  ownership flaps.
- **Load** is roughly one backend call per contender per `ttl`.

| Goal | ttl | transition |
|---|---|---|
| Default | 10s | 6s |
| Faster failover | 3–5s | 2–3s |
| Unreliable network or long GC pauses | 15–30s | ≥ the longest pause you expect |

Keep `transition` larger than the worst backend latency plus GC pause, or ownership will move under load.

## Fencing Tokens

The local lease guard ends ownership on time, but it cannot stop work that is already running when the process
pauses. A scheduler thread that was writing when a 20-second GC pause started will finish that write after another
node took over.

The fix belongs to the resource being protected. Every ownership term has a **fencing token** that increases strictly
from term to term and stays the same across renewals. Pass it with each write; the resource stores the highest token it
has seen and rejects lower ones:

```kotlin
@SimbaScheduled(mutex = "settlement", fixedDelay = "30s")
fun settle(context: ScheduleContext) {
    ledger.settle(batch, fencingToken = context.fencingToken)
}
```

```sql
-- inside the resource: accept the write only from the current or a newer term
update ledger_state
set last_batch = ?, fencing_token = ?
where id = ? and fencing_token <= ?;
```

Tokens are available as `ScheduleContext.fencingToken`, `SimbaScheduler.fencingToken`, `Locker.fencingToken` and
`MutexContendService.fencingToken`; they are `0` when this node does not own the mutex or the backend issues none.
The guarantee only holds if the resource checks the token; Simba cannot enforce it.

| Backend | Token source | Caveat |
|---|---|---|
| JDBC | `fencing_token` column, advanced in the acquire `UPDATE` | Requires the column (`simba.jdbc.fencing=true`, default) |
| Redis | `INCR simba:{mutex}:fence` on acquisition | Monotonic across restarts only with persistence (AOF) |
| Zookeeper | `czxid` of the winning latch node | — |

## Failure Modes

| Event | What happens |
|---|---|
| Owner process crashes | Its lease runs out; another node acquires at `transitionAt` + jitter (Zookeeper: after the session timeout). |
| Owner calls `stop()` | `onReleased` is delivered, the lease is released; Redis and Zookeeper contenders take over immediately, JDBC contenders at their next poll. |
| Owner is paused (GC, stop-the-world) longer than `transition` | Another node acquires. When the paused node resumes, its watchdog has already revoked ownership, but in-flight work completes: **use fencing tokens**. |
| Backend unreachable | Owners keep ownership while the lease is valid, then revoke it. Nobody owns the mutex until the backend is back. |
| Backend slow | Renewals retry within the lease; if none succeeds before it ends, ownership is revoked. |
| Redis primary fails over before replicating | Two nodes can hold leases at the same time. Fencing tokens from a persisted counter still reject the older one. |
| Clock jump on a node | No effect on JDBC (database time) or the watchdog (monotonic clock). On Redis, a node's view of `transitionAt` shifts, which only changes when it retries. |

## Callbacks and Lifecycle

- `onAcquired` / `onReleased` run on the handle executor, one at a time per contender and in order; never under an
  internal lock.
- `stop()` always delivers a final `onReleased` if the contender owned the mutex, and returns after it ran. Do not call
  `stop()` while holding a lock that your callbacks take.
- Services are restartable with the same contender id; notifications from an earlier `start()` are discarded.
- `SimbaScheduler` work is interrupted on `onReleased`; work that ignores interruption keeps running until it returns,
  which is another reason to pass the fencing token.
