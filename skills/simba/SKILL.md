---
name: simba
description: Guide for using Simba 5, the JVM leader-election and distributed-lock library, in applications. Use when adding leader election, leader-only work (runWhileLeader, schedule, @SimbaScheduled), partitioned work (group, @SimbaPartitioned), distributed locks (lock / LockTerm), configuring the JDBC (MySQL, PostgreSQL), Redis or Zookeeper backend, integrating with Spring Boot, using fencing tokens, tuning session timing, or migrating from Simba 4.x. For test-focused work, use the simba-testing skill as well.
---

# Simba 5 — Sessions, Leases and Terms

Simba gives JVM applications leader election and distributed locks over a backend they already run: MySQL 8 or
PostgreSQL 14+ (`simba-jdbc`), Redis 7 (`simba-redis` + `simba-spring-redis`) or Zookeeper (`simba-zookeeper`).
Java 21+; the starter needs Spring Boot 4 (4.1+); Kotlin users need Kotlin 2.2+. Full docs: https://simba.ahoo.me/.

## Model in Five Lines

1. One `Simba` client per process opens **one session** on the backend and keeps it alive every `renewInterval`.
2. Elections, locks and partition groups contend for **leases** (names of 1–128 chars `[A-Za-z0-9._:-]`) attached to
   that session; the session expiring frees them all.
3. Winning a lease gives a **`Term`**: `name`, `token` (fencing token, strictly increasing per name), `isValid`,
   `remaining()`, `ensureValid(min)`, `revoked`.
4. Every `onElected(term)` is followed by exactly one `onRevoked(term, reason)`.
5. A term is a *weakly consistent* view. Never promise "no split-brain": a paused process can still write after its
   term ended. Resources that must reject stale writers check `term.token` (accept `>=` highest, reject lower,
   atomically with the write).

## How to Use This Skill

Identify:
1. The backend the project already runs (JDBC, Redis or Zookeeper). Any is fine; for strict fencing prefer JDBC or
   Zookeeper (Redis tokens can go back after a failover, or after an eviction: run Redis with
   `maxmemory-policy noeviction`).
2. The pattern: callbacks (`election.start`), work per term (`runWhileLeader`), periodic leader work (`schedule`,
   `@SimbaScheduled`), partitions (`group`, `@SimbaPartitioned`), or a lock around a step (`lock`).
3. Spring Boot or plain JVM.

Read `references/backend-internals.md` only to debug backend behavior or explain storage, keys and paths.

## Dependencies

```kotlin
implementation(platform("me.ahoo.simba:simba-bom:${simbaVersion}"))
implementation("me.ahoo.simba:simba-spring-boot-starter")

// one backend:
implementation("me.ahoo.simba:simba-jdbc")              // + spring-boot-starter-jdbc + driver; create tables once
implementation("me.ahoo.simba:simba-spring-redis")      // + spring-boot-starter-data-redis
implementation("me.ahoo.simba:simba-zookeeper")         // + simba.zookeeper.connect-string

// optional (5.1+): coroutine adapters, needs kotlinx-coroutines
implementation("me.ahoo.simba:simba-coroutines")
```

JDBC tables: run `me/ahoo/simba/jdbc/init-simba-mysql.sql` or `init-simba-postgresql.sql` (on the `simba-jdbc`
classpath) once, e.g. via `spring.sql.init.schema-locations`. With several backend modules, set
`simba.backend=jdbc|redis|zookeeper`.

## Spring Boot

The starter (Spring Boot 4.1+) creates one `Simba` bean. Properties:

```yaml
simba:
  backend: redis                 # only with several backend modules
  session:
    lease-duration: 15s          # failover bound after a crash
    renew-interval: 5s           # default lease/3, at most lease/2
    drift: 0.01
    margin: 50ms
  jdbc:
    dialect: mysql               # detected when unset
  redis:
    wait-replicas: 0             # >0: WAIT for replicas on new terms (not on Cluster)
  zookeeper:
    connect-string: localhost:2181
```

Leader-only and partitioned methods:

```java
@SimbaScheduled(name = "report", fixedDelay = "1m")          // method () or (Term)
public void generate(Term term) { reportService.generate(term.getToken()); }

@SimbaPartitioned(group = "billing", partitions = 16, fixedDelay = "1m")   // method (Term, int)
public void bill(Term term, int partition) { billing.run(partition, term.getToken()); }
```

Exactly one of `fixedDelay` / `fixedRate`; `initialDelay` counts from each term's start. Names must be unique per
application. Actuator: endpoint `simba` (snapshot) and health indicator `simba` (`DOWN` when the session was not live
for longer than the lease). Micrometer: `simba.session.renew`, `simba.session.state`, `simba.leadership`,
`simba.locks.held`, `simba.revocations`, `simba.work`.

## Plain JVM

```kotlin
val simba = Simba.builder()
    .backend(JdbcLeaseBackend.mysql(dataSource))          // JdbcLeaseBackend.postgres(...); statement timeout 5 s
    // .backend(RedisLeaseBackend(SpringRedisScripting(connectionFactory)))
    // .backend(ZookeeperLeaseBackend("zk:2181"))
    .config(SimbaConfig.builder().leaseDuration(Duration.ofSeconds(15)).build())
    .listener { event -> log.debug { "$event" } }        // SimbaEventListener
    .build()
// simba.close() revokes every term and frees every lease at once; close the backend afterwards.
```

## Patterns

### Callbacks

```kotlin
val election = simba.election("dispatcher")
election.start(object : LeadershipListener {
    override fun onElected(term: Term) = dispatcher.start(term.token)
    override fun onRevoked(term: Term, reason: RevokeReason) = dispatcher.stop()
})
election.stepDown(Duration.ofSeconds(30))   // give up, stay out for 30 s, keep contending afterwards
election.stop().toCompletableFuture().join() // completes after the last onRevoked
```

Callbacks run serially per election; keep them short. `start` twice for the same name on one client throws.

### Work per term

```kotlin
val task = simba.election("ingest").runWhileLeader { term ->
    while (term.isValid) sink.write(source.next(), fencingToken = term.token)
}
task.close()   // stop: interrupt work, wait for it (max term.remaining()), release, onRevoked
```

### Schedule

```kotlin
simba.election("report").schedule(Schedule.fixedRate(Duration.ofMinutes(1))) { term ->
    report.generate(fencingToken = term.token)
}
```

One virtual thread per term, `ensureValid()` before each run, failures (an `Error` included) logged and published,
loop continues; a `VirtualMachineError` ends the loop and the election steps down for one lease duration.

### Partitions

```kotlin
simba.group("billing", 16).schedule(Schedule.fixedDelay(Duration.ofMinutes(1))) { term, partition -> ... }
```

Members are the group's live sessions; rendezvous hashing assigns partitions; partition `p` is lease `billing.p`.
Every member must use the same partition count.

### Lock

```kotlin
simba.lock("invoice-42").acquire(Duration.ofSeconds(5)).use { term ->   // LockTimeoutException on timeout
    term.ensureValid(Duration.ofSeconds(2))
    store.write(invoice, fencingToken = term.token)
}
simba.lock("invoice-42").tryAcquire()?.use { ... }                    // one attempt, null if held
```

`LockTerm` is the credential, not thread-bound; closing releases. A lock lost early (deadline, expiry, take-over) is
released for good, never re-acquired.

Without blocking (5.1+): `acquireAsync(timeout)` / `tryAcquireAsync()` return a `CompletionStage` that completes on the
callback executor (`LockTimeoutException`, `IllegalStateException` when closed, `IllegalArgumentException` for a
negative timeout); cancelling its `toCompletableFuture()` abandons the attempt and releases a term already won.
Reactor: `Mono.fromCompletionStage(...)`. With `simba-coroutines` (package `me.ahoo.simba.coroutines`):

```kotlin
simba.lock("invoice-42").withLock(Duration.ofSeconds(5)) { term -> store.write(invoice, term.token) }
val term = simba.lock("invoice-42").awaitAcquire(Duration.ofSeconds(5))   // awaitTryAcquire(): one attempt
val reason = term.awaitRevoked()                                          // cancelling the wait leaves the term
```

`SimbaEventFlow` is a `SimbaEventListener` to register with `Simba.builder().listener(...)`; collect its `events`
(a hot `SharedFlow<SimbaEvent>` that drops the oldest events for a slow collector).

## Rules to Follow

- Pass `term.token` to every write on a shared resource; check it atomically there (`update ... where token <= ?`).
- Work must stop on interruption or when `term.isValid` turns false; ignoring interruption delays handover.
- Use `term.ensureValid(expectedStepDuration)` before long steps.
- One `Simba` per process; several only to isolate lease groups from each other's renewal failures.
- Never read a term from shared state: use the `Term` passed to the callback or work block.
- Do not use the same name for a lock and an election (they share one lease).

## Timing

`SimbaConfig.builder().leaseDuration(15s).renewInterval(lease/3).drift(0.01).margin(50ms).retryJitter(renew/2).minBackoff(100ms).build()`. Local deadline = `sentAt + remaining × (1 − drift) − margin`. Constraints: `renewInterval × 2 ≤
leaseDuration` and `leaseDuration × (1 − drift) − margin > renewInterval`. Crash failover ≈ `leaseDuration` + next
candidate attempt (`renewInterval / 2` + jitter); clean stop on Redis/Zookeeper is taken over at once.

## Migrating from 4.x

Not compatible (API, properties, storage): stop every 4.x node before starting 5.0. `MutexContender` →
`LeadershipListener`; `SimbaLocker` → `lock(name)`; `SimbaScheduler` / `@SimbaScheduled(mutex)` →
`election.schedule` / `@SimbaScheduled(name)` with `Term`; `SimbaPartitionedScheduler` → `group(...)` /
`@SimbaPartitioned`; `relinquish` → `stepDown`; `ContendObserver` → `SimbaEventListener`;
`ttl`/`transition` → `simba.session.*`. On JDBC drop the 4.x `simba_member` table before running the 5.x script.
Guide: https://simba.ahoo.me/guide/migration.
