---
title: Quick Start
description: Add Simba to a Spring Boot or plain JVM application, wire a backend, and run an election, leader work, a lock and a partition group.
---

# Quick Start

Simba 5 needs Java 21 or later (work and backend calls run on virtual threads). The starter needs Spring Boot 4
(4.1 or later); the libraries are compiled for Kotlin 2.2 or later.

## Dependencies

Add the Spring Boot starter and **one** backend module.

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
```

:::

`simba-bom` aligns the versions of every Simba module. Without Spring, depend on `simba-core` plus a backend module:
`simba-jdbc`, `simba-zookeeper`, or for Redis `simba-spring-redis` (which brings `simba-redis`). `simba-redis` alone
only defines the `RedisScripting` port: use it without `simba-spring-redis` only with your own adapter for another
Redis client.

## Backend Setup

| Backend | What you provide | Notes |
|---|---|---|
| JDBC | One `DataSource` bean and the three tables | Run [`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-mysql.sql) or [`init-simba-postgresql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-postgresql.sql) once; the dialect is detected from the `DataSource` |
| Redis | One `RedisConnectionFactory` bean (Spring Boot creates it from `spring.data.redis.*`) | Redis 7; standalone, Sentinel or Cluster |
| Zookeeper | `simba.zookeeper.connect-string` | Simba opens its own Zookeeper clients, one per session |

The scripts are on the classpath of `simba-jdbc`, so Spring Boot can run them for you:

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:me/ahoo/simba/jdbc/init-simba-mysql.sql
```

With more than one backend module on the classpath, set `simba.backend=jdbc|redis|zookeeper`; startup fails
otherwise. The [Backends](/guide/backends) page has the schema, the keys and the operating notes of each.

The starter creates one `Simba` bean. Inject it wherever you need an API below.

## Election

The base API: callbacks when this node gains and loses a term.

```kotlin
val election = simba.election("order-dispatcher")
election.start(object : LeadershipListener {
    override fun onElected(term: Term) = dispatcher.start(term.token)
    override fun onRevoked(term: Term, reason: RevokeReason) = dispatcher.stop()
})

// on shutdown: completes after the last onRevoked ran
election.stop().toCompletableFuture().join()
```

Every `onElected(term)` is followed by exactly one `onRevoked(term, reason)`. Keep callbacks short; they run one at a
time per election.

## Leader Work

Usually you want code to run *while* this node leads. `runWhileLeader` runs a block once per term, on its own virtual
thread, and interrupts it when the term ends:

```kotlin
val task = simba.election("report").runWhileLeader { term ->
    while (term.isValid) {
        reportStore.writeChunk(nextChunk(), fencingToken = term.token)
    }
}
// later
task.close()
```

Periodic work on the leader only:

::: code-group

```kotlin [Kotlin]
simba.election("report").schedule(Schedule.fixedDelay(Duration.ofMinutes(1))) { term ->
    reportService.generate(fencingToken = term.token)
}
```

```java [Spring]
@Service
public class ReportJobs {
    @SimbaScheduled(name = "report", fixedDelay = "1m")
    public void generate(Term term) {
        reportService.generate(term.getToken());
    }
}
```

:::

See [Leader Work](/guide/leader-work) for schedules, handover and partition groups.

## Lock

`acquire` returns a `LockTerm`: the credential of the lock. Closing it releases the lease.

```kotlin
simba.lock("invoice-42").acquire(Duration.ofSeconds(5)).use { term ->
    term.ensureValid(Duration.ofSeconds(2))      // enough time left for this step?
    invoiceStore.write(invoice, fencingToken = term.token)
}
```

`acquire` throws `LockTimeoutException` when no term was won in time; `tryAcquire()` makes one attempt and returns
`null` instead. See [Locks](/guide/locks).

## Partition Group

Spread partitions over the live nodes; each partition runs on exactly one node at a time.

::: code-group

```kotlin [Kotlin]
simba.group("billing", partitions = 16).schedule(Schedule.fixedDelay(Duration.ofMinutes(1))) { term, partition ->
    billing.run(shard = partition, fencingToken = term.token)
}
```

```java [Spring]
@SimbaPartitioned(group = "billing", partitions = 16, fixedDelay = "1m")
public void bill(Term term, int partition) {
    billing.run(partition, term.getToken());
}
```

:::

## Without Spring

Build the client yourself over a backend; `Simba` is `AutoCloseable` and closing it frees every lease at once.

```kotlin
val backend = JdbcLeaseBackend.postgres(dataSource)          // or JdbcLeaseBackend.mysql(dataSource)
// val backend = RedisLeaseBackend(SpringRedisScripting(redisConnectionFactory))
// val backend = ZookeeperLeaseBackend("zk1:2181,zk2:2181,zk3:2181")

Simba.builder()
    .backend(backend)
    .config(SimbaConfig(leaseDuration = Duration.ofSeconds(15)))
    .listener { event -> log.debug { "simba: $event" } }
    .build()
    .use { simba ->
        simba.election("report").schedule(Schedule.fixedRate(Duration.ofMinutes(1))) { term -> report(term.token) }
        awaitShutdown()
    }
backend.close()
```

Create one `Simba` per process: every election, lock and group shares its session, so one keep-alive renews all of
them. The [Spring Boot](/guide/configuration#without-spring) page lists the builder options.

## Run the Example

The [example application](https://github.com/Ahoo-Wang/Simba/tree/main/simba-example) uses `@SimbaScheduled`,
`@SimbaPartitioned` and an election against the backend you choose:

```bash
./gradlew simba-example:run -PexampleBackend=redis   # jdbc | redis | zookeeper
```

Start it twice and stop one instance to watch the other take over.
