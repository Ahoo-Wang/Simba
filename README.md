# Simba

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/Simba.svg)](https://github.com/Ahoo-Wang/Simba/releases)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.simba/simba-core)](https://central.sonatype.com/artifact/me.ahoo.simba/simba-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/41f28a111d9c457ab7a9aae6861185eb)](https://www.codacy.com/gh/Ahoo-Wang/Simba/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Ahoo-Wang/Simba&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/Simba/branch/main/graph/badge.svg?token=P9EMJKJ2I5)](https://codecov.io/gh/Ahoo-Wang/Simba)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/Simba)

> [English Documentation](https://simba.ahoo.me/) | [中文文档](https://simba.ahoo.me/zh/) | [中文 README](README.zh-CN.md)

Simba is a JVM library for **leader election and distributed mutual exclusion**. Instances of your application contend
for a named mutex; MySQL (JDBC), Redis or Zookeeper grants one of them a time-bounded lease, and Simba tells each
instance through ordered callbacks when it gains or loses ownership. There is no Simba server to run.

- **At most one local owner per lease.** A node revokes its own ownership when its lease ends without renewal, even
  while a backend call hangs.
- **Fencing tokens.** Every ownership term carries a strictly increasing token. A process paused by GC can resume
  after its lease ended; resources that must reject such a stale owner check the token. Simba cannot do that for you.
- **Three APIs** on one contend service: `@SimbaScheduled` / `SimbaScheduler` (leader-only work), `SimbaLocker`
  (blocking lock), `MutexContender` (callbacks).

Read [Correctness](https://simba.ahoo.me/guide/correctness) before protecting anything that must never be written twice.

## Installation

Add the Spring Boot starter and **one** backend (Java 17+):

```kotlin
implementation("me.ahoo.simba:simba-spring-boot-starter:${simbaVersion}")

// JDBC (MySQL): also run simba-jdbc/src/init-script/init-simba-mysql.sql once
implementation("me.ahoo.simba:simba-jdbc:${simbaVersion}")
implementation("org.springframework.boot:spring-boot-starter-jdbc")
runtimeOnly("com.mysql:mysql-connector-j")

// or Redis
implementation("me.ahoo.simba:simba-spring-redis:${simbaVersion}")
implementation("org.springframework.boot:spring-boot-starter-data-redis")

// or Zookeeper: also define a started CuratorFramework bean
implementation("me.ahoo.simba:simba-zookeeper:${simbaVersion}")
```

The starter configures the backend whose infrastructure bean (`DataSource`, `StringRedisTemplate` or
`CuratorFramework`) exists. With several backend modules on the classpath, set `simba.backend=jdbc|redis|zookeeper`.
Maven coordinates, the JDBC schema and use without Spring are in the
[Quick Start](https://simba.ahoo.me/guide/quick-start).

## Usage

### Leader-only scheduling

```java
@Service
public class ReportJobs {
    @SimbaScheduled(mutex = "report", fixedDelay = "1m")
    public void generate(ScheduleContext context) {
        reportService.generate(context.getFencingToken());
    }
}
```

The method runs on the leader only; it is interrupted when the node loses leadership. Without Spring, use
`SimbaScheduler(mutex, factory, ScheduleConfig.delay(...)) { context -> ... }`.

### SimbaLocker

```java
try (Locker locker = new SimbaLocker("nightly-migration", mutexContendServiceFactory)) {
    locker.acquire(Duration.ofSeconds(30));
    migrate(locker.getFencingToken());
}
```

### MutexContender

```java
MutexContendService contendService = mutexContendServiceFactory.createMutexContendService(
    new AbstractMutexContender("coordinator") {
        @Override
        public void onAcquired(MutexState mutexState) {
            startCoordinating();
        }

        @Override
        public void onReleased(MutexState mutexState) {
            stopCoordinating();
        }
    });
contendService.start();
// on shutdown
contendService.stop();
```

## Documentation

| | |
|---|---|
| [Introduction](https://simba.ahoo.me/guide/) | Guarantees, choosing an API and a backend |
| [Backends](https://simba.ahoo.me/guide/backends) | JDBC, Redis, Zookeeper: storage, setup, operating notes |
| [Correctness](https://simba.ahoo.me/guide/correctness) | Leases, ttl/transition, fencing tokens, failure modes |
| [Configuration](https://simba.ahoo.me/guide/configuration) | `simba.*` properties and factories |
| [Observability](https://simba.ahoo.me/guide/observability) | Micrometer metrics, `simba` Actuator endpoint, alerts |
| [Upgrading](https://simba.ahoo.me/guide/upgrading) | Upgrading to 4.x |
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
