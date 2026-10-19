# Simba

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/Simba.svg)](https://github.com/Ahoo-Wang/Simba/releases)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.simba/simba-core)](https://central.sonatype.com/artifact/me.ahoo.simba/simba-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/41f28a111d9c457ab7a9aae6861185eb)](https://www.codacy.com/gh/Ahoo-Wang/Simba/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Ahoo-Wang/Simba&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/Simba/branch/main/graph/badge.svg?token=P9EMJKJ2I5)](https://codecov.io/gh/Ahoo-Wang/Simba)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/Simba)

> [English Documentation](https://simba.ahoo.me/) | [中文文档](https://simba.ahoo.me/zh/) | [中文 README](README.zh-CN.md)

Simba is a JVM library for **leader election and distributed locks**. Each process opens one session on a backend you
already operate — MySQL 8 or PostgreSQL 14+ (JDBC), Redis 7, or Zookeeper — and contends for named leases on it. The
node that wins a lease gets a `Term`: its fencing token and how long it is still valid. There is no Simba server to
run.

- **At most one valid term per name**, as far as the local clock can tell: the local deadline is computed from when
  each keep-alive was *sent* and always passes before the backend can hand the lease to someone else.
- **Fencing tokens.** Every term carries a token that strictly increases per name. A process paused by GC can resume
  after its term ended; resources that must reject such a stale owner check the token. Simba cannot do that for you,
  and does not promise "no split-brain".
- **One session per node.** One keep-alive renews every election, lock and partition of the process.
- **Work bound to the term.** Leader work runs on virtual threads, is interrupted when the term ends, and finishes
  before the lease is released.

Read [Concepts and Correctness](https://simba.ahoo.me/guide/correctness) before protecting anything that must never be
written twice. Coming from 4.x? See [Migrating to 5.0](https://simba.ahoo.me/guide/migration).

## Installation

Java 21+. Add the Spring Boot starter and **one** backend:

```kotlin
implementation("me.ahoo.simba:simba-spring-boot-starter:${simbaVersion}")

// JDBC (MySQL 8 or PostgreSQL 14+): also create the tables once with
// me/ahoo/simba/jdbc/init-simba-mysql.sql or init-simba-postgresql.sql from simba-jdbc
implementation("me.ahoo.simba:simba-jdbc:${simbaVersion}")
implementation("org.springframework.boot:spring-boot-starter-jdbc")
runtimeOnly("com.mysql:mysql-connector-j") // or org.postgresql:postgresql

// or Redis 7
implementation("me.ahoo.simba:simba-spring-redis:${simbaVersion}")
implementation("org.springframework.boot:spring-boot-starter-data-redis")

// or Zookeeper: set simba.zookeeper.connect-string
implementation("me.ahoo.simba:simba-zookeeper:${simbaVersion}")
```

The starter builds one `Simba` bean over the backend on the classpath. With several backend modules, set
`simba.backend=jdbc|redis|zookeeper`. Without Spring:

```kotlin
val simba = Simba.builder()
    .backend(JdbcLeaseBackend.postgres(dataSource))   // or RedisLeaseBackend / ZookeeperLeaseBackend
    .build()                                          // AutoCloseable: close() frees every lease at once
```

## Usage

### Election

```kotlin
simba.election("order-dispatcher").start(object : LeadershipListener {
    override fun onElected(term: Term) = dispatcher.start(term.token)
    override fun onRevoked(term: Term, reason: RevokeReason) = dispatcher.stop()
})
```

Every `onElected(term)` is followed by exactly one `onRevoked(term, reason)`.

### Leader work

```kotlin
simba.election("ingest").runWhileLeader { term ->
    while (term.isValid) sink.write(source.next(), fencingToken = term.token)
}
```

The block runs once per term and is interrupted when the term ends; `stop()` waits for it before releasing the lease.

### Leader-only scheduling

```java
@Service
public class ReportJobs {
    @SimbaScheduled(name = "report", fixedDelay = "1m")
    public void generate(Term term) {
        reportService.generate(term.getToken());
    }
}
```

Without Spring: `simba.election("report").schedule(Schedule.fixedDelay(Duration.ofMinutes(1))) { term -> ... }`.

### Partitions

```java
@SimbaPartitioned(group = "billing", partitions = 16, fixedDelay = "1m")
public void bill(Term term, int partition) {
    billing.run(partition, term.getToken());
}
```

Each of the 16 partitions runs on one live node at a time. Without Spring: `simba.group("billing", 16).schedule(...)`.

### Lock

```kotlin
simba.lock("invoice-42").acquire(Duration.ofSeconds(5)).use { term ->
    term.ensureValid(Duration.ofSeconds(2))
    invoiceStore.write(invoice, fencingToken = term.token)
}
```

The returned `LockTerm` is the credential: closing it releases the lease. Since 5.1, `acquireAsync` waits without
blocking a thread; cancelling its future abandons the attempt:

```kotlin
simba.lock("invoice-42").acquireAsync(Duration.ofSeconds(5))
    .thenCompose { term -> invoiceStore.writeAsync(invoice, term.token).whenComplete { _, _ -> term.releaseAsync() } }
```

### Observability

`simba.snapshot()` shows the session and every election of the node; the starter adds Micrometer metrics, the
`simba` Actuator endpoint and the `simba` health indicator.

### Testing

`simba-testing` provides an in-memory backend and virtual time for your unit tests; `simba-tck` provides `FencingSpec`
to verify your token-checking resource and `LeaseBackendSpec` for backends.

## Documentation

| | |
|---|---|
| [Introduction](https://simba.ahoo.me/guide/) | Guarantees, choosing an API and a backend |
| [Quick Start](https://simba.ahoo.me/guide/quick-start) | Dependencies, backend setup, first code for each API |
| [Concepts and Correctness](https://simba.ahoo.me/guide/correctness) | Sessions, leases, terms, fencing tokens, handover, failure modes |
| [Backends](https://simba.ahoo.me/guide/backends) | JDBC, Redis, Zookeeper: schema, keys, paths, operating notes |
| [Spring Boot](https://simba.ahoo.me/guide/configuration) | `simba.*` properties, beans, shutdown order |
| [Leader Work and Partitions](https://simba.ahoo.me/guide/leader-work) | `runWhileLeader`, schedules, partition groups, annotations |
| [Locks](https://simba.ahoo.me/guide/locks) | `lock(name)` and `LockTerm` |
| [Observability](https://simba.ahoo.me/guide/observability) | Snapshot, events, metrics, Actuator endpoint, health |
| [Testing](https://simba.ahoo.me/guide/testing) | In-memory backend, virtual time, TCK |
| [Migrating from 4.x](https://simba.ahoo.me/guide/migration) | API, property and storage mapping |
| [API Reference](https://simba.ahoo.me/api/) | Public types |
| [Architecture](https://simba.ahoo.me/architecture/), [ADRs](docs/adr/) | Internals and design decisions |

The [example application](simba-example) runs against any backend.

## Used By

- [Govern-EventBus](https://github.com/Ahoo-Wang/govern-eventbus/tree/master/eventbus-core/src/main/java/me/ahoo/eventbus/core/compensate)
- [CoSky](https://github.com/Ahoo-Wang/CoSky/blob/main/cosky-rest-api/src/main/kotlin/me/ahoo/cosky/rest/stat/StatServiceScheduler.kt)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow, quality gates and release process, the
[contributor guide](https://simba.ahoo.me/contributing/) for tests and the backend TCK, and
[SECURITY.md](SECURITY.md) to report vulnerabilities.
