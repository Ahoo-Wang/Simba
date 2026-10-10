---
title: Introduction
description: What Simba is, what it guarantees and what it does not, and which API and backend to pick.
---

# Introduction

Simba is a JVM library for **leader election and distributed mutual exclusion**. Every instance of your application
contends for a named *mutex*; the backend (MySQL, Redis or Zookeeper) grants one of them a time-bounded *lease*, and
Simba tells each instance through callbacks when it gains or loses ownership. There is no Simba server: the library
runs inside your application and uses storage you already operate.

Typical uses: run a scheduled job on one node only, have one consumer drive a process, or elect a coordinator.

## What Simba Guarantees

1. **At most one local owner per lease.** For a given mutex, at most one contender *believes* it is the owner while
   its lease is valid. Each node revokes its own ownership when its lease ends without a successful renewal, even if
   the backend call hangs.
2. **Liveness.** When the owner stops, crashes or loses connectivity, another contender takes over after the lease
   ends (Zookeeper: when the session expires).
3. **Ordered callbacks.** `onAcquired` and `onReleased` are delivered in order per contender, outside internal locks,
   and `stop()` always delivers a final `onReleased`.
4. **Fencing tokens.** Every ownership term carries a token that increases strictly from term to term on every
   backend (`0` means none).

## What Simba Does Not Guarantee

Local ownership is a *weakly consistent view* of the backend. A process paused by GC, a stalled disk or a network
partition can resume after its lease ended and still finish work it already started, while another node owns the
mutex. No lease-based lock can prevent that on its own.

If overlapping work is harmful, the protected resource must reject stale owners: pass the
[fencing token](/guide/correctness#fencing-tokens) with every write and let the resource refuse tokens lower than the
highest it has seen. If duplicated work is merely wasteful (a report generated twice), the lease alone is enough.

## Choose an API

| You want to... | Use | Page |
|---|---|---|
| Run a method periodically on the leader only | `@SimbaScheduled` (Spring) or `SimbaScheduler` | [Quick Start](/guide/quick-start#leader-only-scheduling) |
| Block a thread until it holds the lock, then release it | `SimbaLocker` | [Quick Start](/guide/quick-start#simbalocker) |
| React to leadership changes for as long as the application runs | `MutexContender` + `MutexContendService` | [Quick Start](/guide/quick-start#mutexcontender) |

All three are built on the same contend service; the [API reference](/api/) documents each type.

## Choose a Backend

| | JDBC (MySQL) | Redis | Zookeeper |
|---|---|---|---|
| Mechanism | One row per mutex, conditional `UPDATE` | `SET NX PX` via Lua, pub/sub on release | Curator `LeaderLatch` |
| Failover after a crash | End of the lease (`ttl + transition`) + jitter | End of the lease + jitter | Session timeout |
| Failover after a clean stop | Next contender poll (up to the lease end + jitter) | Immediate (release broadcast) | Immediate |
| Clock | Database time | Redis `PTTL` + local clock | None (ephemeral nodes) |
| Fencing token durability | Transactional | Needs Redis persistence (AOF) | ZooKeeper `czxid` |

Pick the one you already run. The [Backends](/guide/backends) page covers setup and operating notes for each.

## Where to Next

- [Quick Start](/guide/quick-start): add the dependency and run code.
- [Correctness](/guide/correctness): leases, timing, fencing tokens and failure modes. Read this before protecting
  anything that must not be written twice.
- [Configuration](/guide/configuration), [Observability](/guide/observability), [Upgrading](/guide/upgrading).
- [Architecture](/architecture/) and [Contributing](/contributing/) if you work on Simba itself.
