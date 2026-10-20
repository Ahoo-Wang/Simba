---
title: Quick Start
description: Add Simba to a Spring Boot or plain JVM application, wire a backend, and run a leader-only job, leader work, a lock, a partition group and a bare election.
---

# Quick Start

From zero to a job that runs on exactly one node, then the other APIs in increasing order of control.

**Requirements:** Java 21 or later (work and backend calls run on virtual threads). The starter needs Spring Boot 4.1
or later; the libraries are compiled for Kotlin 2.2 or later.

## 1. Add the Dependencies

The Spring Boot starter plus **one** backend module:

::: code-group

```kotlin [Gradle (Kotlin)]
implementation("me.ahoo.simba:simba-spring-boot-starter:${simbaVersion}")

// JDBC: MySQL 8 or PostgreSQL 14+
implementation("me.ahoo.simba:simba-jdbc:${simbaVersion}")
implementation("org.springframework.boot:spring-boot-starter-jdbc")
runtimeOnly("com.mysql:mysql-connector-j") // or org.postgresql:postgresql

// or Redis 7 (through Spring Data Redis)
implementation("me.ahoo.simba:simba-spring-redis:${simbaVersion}")
implementation("org.springframework.boot:spring-boot-starter-data-redis")

// or Zookeeper
implementation("me.ahoo.simba:simba-zookeeper:${simbaVersion}")

// optional, since 5.1: suspending lock calls, withLock, event Flow
implementation("me.ahoo.simba:simba-coroutines:${simbaVersion}")
```

```xml [Maven]
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-boot-starter</artifactId>
    <version>${simba.version}</version>
</dependency>
<!-- plus one of: simba-jdbc, simba-spring-redis, simba-zookeeper -->
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-redis</artifactId>
    <version>${simba.version}</version>
</dependency>
<!-- optional, since 5.1: coroutine adapters -->
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-coroutines</artifactId>
    <version>${simba.version}</version>
</dependency>
```

:::

`simba-bom` aligns the versions of every Simba module. Without Spring, depend on `simba-core` plus a backend module:
`simba-jdbc`, `simba-zookeeper`, or for Redis `simba-spring-redis` (which brings `simba-redis`). `simba-redis` alone
only defines the `RedisScripting` port; use it without `simba-spring-redis` only with your own adapter for another
Redis client.

## 2. Point It at a Backend

| Backend | What you provide |
|---|---|
| JDBC | One `DataSource` bean, plus the three tables created once with [`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-mysql.sql) or [`init-simba-postgresql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-postgresql.sql); the dialect is detected from the `DataSource` |
| Redis | One `RedisConnectionFactory` bean (Spring Boot creates it from `spring.data.redis.*`); standalone, Sentinel or Cluster |
| Zookeeper | `simba.zookeeper.connect-string`; Simba opens its own Zookeeper clients, one per session |

The JDBC scripts are on the classpath of `simba-jdbc`, so Spring Boot can run them for you:

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:me/ahoo/simba/jdbc/init-simba-mysql.sql
```

With more than one backend module on the classpath, set `simba.backend=jdbc|redis|zookeeper`, or startup fails. The
starter now creates one `Simba` bean; inject it wherever you use an API below. Storage layout and operating notes of
each backend are on [Backends](/guide/backends).

## 3. Run a Job on One Node

The most common need: a periodic job that must not run on two nodes at once.

::: code-group

```java [Spring]
@Service
public class ReportJobs {
    @SimbaScheduled(name = "report", fixedDelay = "1m")
    public void generate(Term term) {
        reportService.generate(term.getToken());
    }
}
```

```kotlin [Kotlin]
simba.election("report").schedule(Schedule.fixedDelay(Duration.ofMinutes(1))) { term ->
    reportService.generate(fencingToken = term.token)
}
```

:::

Start two instances: one leads `report` and runs the job, the other waits. Stop the leader and the other takes over.
The `Term` the job receives carries the fencing token; pass it to whatever the job writes (see
[Fencing and Failures](/guide/correctness)).

## 4. The Other APIs

### Work While Leading

`runWhileLeader` runs a block once per term, on its own virtual thread, and interrupts it when the term ends:

```kotlin
val task = simba.election("ingest").runWhileLeader { term ->
    while (term.isValid) {
        sink.write(source.poll(), fencingToken = term.token)
    }
}
// later: stops the election, waits for the block to exit, then releases the lease
task.close()
```

### Partitions over the Live Nodes

Each of 16 partitions runs on exactly one node at a time; nodes joining or leaving move only their share:

::: code-group

```java [Spring]
@SimbaPartitioned(group = "billing", partitions = 16, fixedDelay = "1m")
public void bill(Term term, int partition) {
    billing.run(partition, term.getToken());
}
```

```kotlin [Kotlin]
simba.group("billing", partitions = 16).schedule(Schedule.fixedDelay(Duration.ofMinutes(1))) { term, partition ->
    billing.run(shard = partition, fencingToken = term.token)
}
```

:::

### A Lock around One Step

`acquire` returns a `LockTerm`; closing it releases the lease:

```kotlin
simba.lock("invoice-42").acquire(Duration.ofSeconds(5)).use { term ->
    term.ensureValid(Duration.ofSeconds(2))      // enough time left for this step?
    invoiceStore.write(invoice, fencingToken = term.token)
}
```

`acquire` throws `LockTimeoutException` when no term was won in time; `tryAcquire()` makes one attempt and returns
`null` instead.

### A Bare Election

When you manage the work yourself, listen for terms:

```kotlin
val election = simba.election("order-dispatcher")
election.start(object : LeadershipListener {
    override fun onElected(term: Term) = dispatcher.start(term.token)
    override fun onRevoked(term: Term, reason: RevokeReason) = dispatcher.stop()
})

// on shutdown: completes after the last onRevoked ran
election.stop().toCompletableFuture().join()
```

Every `onElected(term)` is followed by exactly one `onRevoked(term, reason)`. Keep callbacks short: they run one at a
time per election.

The details of each are on [Leader Work](/guide/leader-work) and [Locks](/guide/locks).

## Without Spring

Build the client over a backend yourself. `Simba` is `AutoCloseable`; closing it frees every lease at once.

```kotlin
val backend = JdbcLeaseBackend.postgres(dataSource)          // or JdbcLeaseBackend.mysql(dataSource)
// val backend = RedisLeaseBackend(SpringRedisScripting(redisConnectionFactory))
// val backend = ZookeeperLeaseBackend("zk1:2181,zk2:2181,zk3:2181")

Simba.builder()
    .backend(backend)
    .config(SimbaConfig.builder().leaseDuration(Duration.ofSeconds(15)).build())
    .listener { event -> log.debug { "simba: $event" } }
    .build()
    .use { simba ->
        simba.election("report").schedule(Schedule.fixedRate(Duration.ofMinutes(1))) { term -> report(term.token) }
        awaitShutdown()
    }
backend.close()
```

Create **one** `Simba` per process: every election, lock and group shares its session, so one keep-alive renews all of
them. The builder options are listed under [Without Spring](/guide/configuration#without-spring).

## Run the Example

The [example application](https://github.com/Ahoo-Wang/Simba/tree/main/simba-example) uses `@SimbaScheduled`,
`@SimbaPartitioned` and an election against the backend you choose:

```bash
./gradlew simba-example:run -PexampleBackend=redis
```

`-PexampleBackend` takes `jdbc`, `redis` or `zookeeper`. Start it twice and stop one instance to watch the other take
over.
