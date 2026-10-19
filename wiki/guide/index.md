---
title: Introduction
description: What Simba is, what it guarantees and what it does not, and which API and backend to pick.
---

# Introduction

Simba is a JVM library for **leader election and distributed locks**. Each process opens one *session* on a backend
you already operate (MySQL or PostgreSQL, Redis, or Zookeeper) and contends for named *leases* on it. The node that
wins a lease gets a *term*: an object that carries a fencing token and knows how long it is still valid. There is no
Simba server: the library runs inside your application.

Typical uses: run a scheduled job on one node only, spread partitions of work over the live nodes, have one process
drive a workflow, or hold a lock around a step that must not run twice at once.

## What Simba Guarantees

1. **At most one valid term per lease name, as far as the local clock can tell.** A term is valid until the session's
   local deadline, which always comes before the backend could hand the lease to another session (as long as clock
   rates differ by less than the configured `drift`). Validity is computed from the monotonic clock on every read, so
   a process waking from a long pause sees its term as invalid on the first read.
2. **Fencing tokens.** Every term carries a token greater than zero that strictly increases from term to term of the
   same name, on every backend.
3. **Paired callbacks.** Every `onElected(term)` is followed by exactly one `onRevoked(term, reason)`, callbacks of one
   election run one at a time in order, and none arrives after `stop()` completed.
4. **Ordered handover.** `stop()` and `stepDown()` interrupt the work bound to the term, wait for it to exit (at most
   for the term's remaining time), and only then release the lease.
5. **Liveness.** A node that closes its client frees its leases at once; when a node crashes or loses the backend,
   its session expires after `leaseDuration` and the other nodes take its leases over.

## What Simba Does Not Guarantee

A term is a *weakly consistent view* of the backend. A process paused by GC, a stalled disk or a network partition can
resume after its session expired and still finish a write it already started, while another node holds a newer term.
No lease-based lock can prevent that on its own; Simba never promises "no split-brain".

If overlapping work is harmful, the protected resource must reject stale owners: send
[`term.token`](/guide/correctness#fencing-tokens) with every write and let the resource refuse tokens lower than the
highest it has accepted. If duplicated work is merely wasteful (a report generated twice), the term alone is enough.

Simba also does not keep working while the backend is unreachable: once the session's local deadline passes, every
term of that node is revoked.

## Choose an API

All APIs hang off one `Simba` client (one per process, one backend session).

| You want to... | Use | Page |
|---|---|---|
| React to gaining and losing leadership | `simba.election(name).start(listener)` | [Quick Start](/guide/quick-start#election) |
| Run a block once per term, interrupted when the term ends | `election.runWhileLeader { term -> }` | [Leader Work](/guide/leader-work#run-while-leader) |
| Run a method periodically on the leader only | `election.schedule(...)` or `@SimbaScheduled` | [Leader Work](/guide/leader-work#schedules) |
| Spread N partitions over the live nodes, each partition on one node | `simba.group(name, n)` or `@SimbaPartitioned` | [Leader Work](/guide/leader-work#partition-groups) |
| Hold a lock around a step, then release it | `simba.lock(name).acquire(timeout)` | [Locks](/guide/locks) |
| See the session and every election of this node | `simba.snapshot()`, Actuator `simba` | [Observability](/guide/observability) |

## Choose a Backend

| | JDBC (MySQL 8, PostgreSQL 14+) | Redis 7 | Zookeeper |
|---|---|---|---|
| Session | Row in `simba_session` | Hash `simba:session:{sid}` with `PX` | A Zookeeper session |
| Lease | Row in `simba_lease`, never deleted | Hash `simba:lease:{name}`, never expires | Ephemeral node `/simba/leases/{name}` |
| Fencing token | Counter in the row, `+1` per term | Counter in the hash, `+1` per term | The node's `czxid` |
| Clock | Database time | Redis `TIME` | Zookeeper session timeout |
| Failover after a clean stop | Next candidate attempt | Immediate (release broadcast) | Immediate (node watch) |
| Token durability | Transactional | Can go back after a failover | Zookeeper quorum |

Failover after a crash takes about `leaseDuration` (Zookeeper: the negotiated session timeout) plus the next
candidate attempt. Pick the one you already run; read
[Backends](/guide/backends) for the setup and operating notes, in particular the
[Redis failover boundary](/guide/backends#token-regression-after-a-failover).

## Where to Next

- [Quick Start](/guide/quick-start): add the dependency and run code.
- [Concepts and Correctness](/guide/correctness): sessions, leases, terms, tokens and the handover order. Read this
  before protecting anything that must not be written twice.
- [Spring Boot](/guide/configuration), [Leader Work](/guide/leader-work), [Locks](/guide/locks),
  [Observability](/guide/observability), [Testing](/guide/testing).
- Coming from Simba 4.x: [Migrating to 5.0](/guide/migration).
- [Architecture](/architecture/) and [Contributing](/contributing/) if you work on Simba itself.
