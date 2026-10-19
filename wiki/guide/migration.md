---
title: Migrating from 4.x to 5.0
description: Map Simba 4.x APIs, properties, schema and Redis keys to Simba 5.0, and switch over with a short stop of every node.
---

# Migrating from 4.x to 5.0

Simba 5 is a rewrite ([ADR 0010](https://github.com/Ahoo-Wang/Simba/blob/main/docs/adr/0010-simba-5-target-architecture.md)):
one session per process instead of one lease per mutex, a `Term` object instead of owner state, and one engine for
all three backends. It is **not compatible** with 4.x in source, in binary, or on the wire:

- the JDBC tables, Redis keys and Zookeeper paths are different, so a 4.x node and a 5.x node do not see each other's
  leases and would both lead;
- the configuration properties and the Spring beans are different;
- Java 21 is required.

You therefore cannot roll the upgrade node by node. **Stop every node that contends for the same names, then start
them on 5.0.** If a short double run would be harmful, make sure no 4.x node is running before the first 5.0 node
starts.

## Steps

1. Update the code with the [API mapping](#api-mapping) below and the configuration with the
   [property mapping](#property-mapping).
2. Prepare the backend:
   - **JDBC:** create the 5.x tables with
     [`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-mysql.sql)
     or
     [`init-simba-postgresql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-postgresql.sql).
     The 4.x table `simba_member(group_name, member_id, expire_at)` has the same name as the 5.x table
     `simba_member(group_name, session_id)` but different columns, and the scripts use `create table if not exists`:
     **drop or rename the 4.x `simba_member` first**, or use a separate database or schema.
   - **Redis, Zookeeper:** nothing to create. The 5.x keys and nodes do not overlap the 4.x ones, except that 4.x
     latch nodes also live under `/simba`; a 4.x mutex named `leases` or `groups` collides with the 5.x
     subtrees, so remove the old nodes or set `simba.zookeeper.root` to another path.
3. Stop every 4.x node.
4. Start the 5.0 nodes.
5. Remove the 4.x storage once you no longer need to roll back: table `simba_mutex` (and the 4.x `simba_member`),
   keys `simba:{mutex}`, `simba:{mutex}:fence`, `simba:{mutex}:token`, `simba:{group}:members`, Zookeeper nodes
   `/simba/{mutex}` and `/simba-members`.

Fencing tokens restart: 5.x tokens come from the new storage and start at `1` (Zookeeper: the new nodes' `czxid`). A
resource that recorded 4.x tokens would reject the first 5.x writes; reset its highest token during the switch, or
start the 5.x tokens above it (for JDBC, by inserting `simba_lease` rows with a higher `token` and an empty
`session_id` before the first start).

## API Mapping

| 4.x | 5.0 |
|---|---|
| `MutexContendServiceFactory` + `MutexContender` + `start()` | `simba.election(name).start(listener)` |
| `AbstractMutexContender.onAcquired(mutexState)` / `onReleased(mutexState)` | `LeadershipListener.onElected(term)` / `onRevoked(term, reason)` |
| `MutexContendService.stop()` | `election.stop()` (a `CompletionStage`) |
| `relinquish(cooldown)` | `election.stepDown(cooldown)` |
| `MutexOwner` / `MutexState` | `Term` (this node's term); no view of other nodes' ownership |
| `fencingToken`, `leaseRemaining`, `ensureLease()` on services, lockers, schedulers, contexts | `term.token`, `term.remaining()`, `term.ensureValid()` on the `Term` the work received |
| `isOwner`, `hasOwner()` | `term.isValid`; `simba.snapshot()` for this node's elections |
| `contenderId` (reused across restarts) | `SessionId` (unique per incarnation, never reused) |
| `SimbaLocker(mutex, factory).acquire(timeout)` | `simba.lock(name).acquire(timeout)` returning a `LockTerm` |
| `SimbaScheduler` / `AbstractScheduler` + `ScheduleConfig.delay/rate` | `election.schedule(Schedule.fixedDelay/fixedRate) { term -> }` |
| `ScheduleContext` | The `Term` passed to the work |
| `SimbaPartitionedScheduler` + `MemberRegistry` | `simba.group(name, partitions).schedule { term, partition -> }` |
| `PartitionContext` | `(term, partition)` |
| `@SimbaScheduled(mutex = ..., worker = ...)` with `ScheduleContext` | `@SimbaScheduled(name = ...)` with `Term` |
| — | `@SimbaPartitioned(group, partitions, ...)` with `(Term, Int)` |
| `ContendObserver` | `SimbaEventListener` receiving `SimbaEvent`s |
| `MutexLeaseStore`, `MutexOwnerRepository`, `MemberRegistry` (SPIs) | `LeaseBackend` |
| `JdbcMutexContendServiceFactory` | `JdbcLeaseBackend.mysql(dataSource)` / `postgres(dataSource)` |
| `SpringRedisMutexContendServiceFactory` | `RedisLeaseBackend(SpringRedisScripting(connectionFactory))` |
| `ZookeeperMutexContendServiceFactory` (Curator `LeaderLatch`) | `ZookeeperLeaseBackend(connectString)` (ephemeral nodes) |
| `MutexContendServiceSpec`, `LockSpec`, `MemberRegistrySpec` (`simba-test`) | `LeaseBackendSpec` (`simba-tck`) |
| `FencingSpec` (`simba-test`) | `FencingSpec` (`simba-tck`) |
| — | `simba-testing`: `InMemoryLeaseBackend`, `VirtualScheduler`, `Simulation` |

Behavior that changed:

- **Every term is a new object.** A callback or a work block only ever sees its own term, so it cannot read a later
  term's token by accident.
- **One session per node.** One keep-alive renews every lease and membership; one renewal failure affects them all.
  Create several `Simba` clients if you need isolation.
- **Locks are not bound to threads.** A `LockTerm` may be closed from any thread; a lock lost early is not
  re-acquired.
- **No push of other nodes' ownership.** 4.x notified every contender of other nodes' acquisitions; 5.x reports only
  this node's terms.
- **Zookeeper leases are bounded** like the others: `remaining()` has the same meaning on all three backends, and
  FIFO fairness of `LeaderLatch` is gone.
- **Leader work runs on virtual threads**, one per term; there is no thread pool per scheduler.

## Property Mapping

| 4.x | 5.0 |
|---|---|
| `simba.enabled`, `simba.backend`, `simba.<backend>.enabled` | Unchanged |
| `simba.jdbc.ttl`, `simba.redis.ttl` + `transition` | `simba.session.lease-duration` and `simba.session.renew-interval`, for every backend |
| `simba.jdbc.initial-delay` | Removed |
| `simba.jdbc.fencing` | Removed: tokens are always on |
| — | `simba.session.drift`, `simba.session.margin` |
| — | `simba.jdbc.dialect`, `simba.jdbc.query-timeout` |
| — | `simba.redis.wait-replicas`, `simba.redis.wait-timeout`, `simba.redis.route-by-slot` |
| `CuratorFramework` bean (Zookeeper) | `simba.zookeeper.connect-string`, `root`, `connection-timeout` |
| `simba.scheduling.enabled`, `simba.metrics.enabled` | Unchanged |

As a starting point, `lease-duration ≈ ttl + transition` keeps the failover time of 4.x.

## Storage Mapping

| | 4.x | 5.0 |
|---|---|---|
| JDBC | `simba_mutex` (one row per mutex), `simba_member(group_name, member_id, expire_at)` | `simba_session`, `simba_lease`, `simba_member(group_name, session_id)` |
| Redis | `simba:{mutex}`, `simba:{mutex}:fence`, `simba:{mutex}:token`, `simba:{group}:members`, channel `simba:{mutex}` | `simba:lease:{name}`, `simba:session:{sid}` (+ `:leases`, `:groups`), `simba:group:{group}` |
| Zookeeper | `LeaderLatch` at `/simba/{mutex}`, members `/simba-members/{group}/{memberId}` | `/simba/leases/{name}`, `/simba/groups/{group}/{sessionId}` |

Partition leases keep the `{group}.{partition}` names and the same rendezvous weights, but members are now session
ids, so the assignment of partitions to nodes changes.

## Metrics and Endpoint Mapping

| 4.x | 5.0 |
|---|---|
| `simba.mutex.owner{mutex}` | `simba.leadership{name}` |
| `simba.mutex.ownership.changes{mutex,change}` | `simba.revocations{reason}` (every ended term, `stopped` and `stepped_down` included) |
| `simba.mutex.contend{mutex,operation,outcome}` | `simba.session.renew` (one keep-alive per node) |
| `simba.mutex.lease.expired{mutex}` | `simba.revocations{reason="deadline_passed"}` |
| `simba.scheduler.work{mutex,outcome}` | `simba.work{name,outcome}` |
| — | `simba.session.state{state}` |
| `/actuator/simba` per mutex and contender | `/actuator/simba`: session plus elections, from `snapshot()` |
| `simba` health: stale contenders | `simba` health: session not live for longer than the lease |

Update dashboards and the [alert rules](/guide/observability#alert-rules) accordingly.
