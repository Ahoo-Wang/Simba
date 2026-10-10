# Simba(Distributed Mutex)

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/Simba.svg)](https://github.com/Ahoo-Wang/Simba/releases)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.simba/simba-core)](https://central.sonatype.com/artifact/me.ahoo.simba/simba-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/41f28a111d9c457ab7a9aae6861185eb)](https://www.codacy.com/gh/Ahoo-Wang/Simba/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Ahoo-Wang/Simba&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/Simba/branch/main/graph/badge.svg?token=P9EMJKJ2I5)](https://codecov.io/gh/Ahoo-Wang/Simba)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/Simba)

> [中文文档](https://simba.ahoo.me/zh/) | [English Document](https://simba.ahoo.me/)

## Introduction

Simba aims to provide easy-to-use and flexible distributed lock services and supports multiple storage implementations: relational databases, Redis, and Zookeeper.

## Installation

### Gradle

> Kotlin DSL

``` kotlin
    implementation("me.ahoo.simba:simba-spring-boot-starter:${simbaVersion}")
```

### Maven

```xml
<?xml version="1.0" encoding="UTF-8"?>

<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">

    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.1.0</version>
        <relativePath/>
    </parent>
    <artifactId>demo</artifactId>
    <properties>
        <simba.version>simbaVersion</simba.version>
    </properties>

    <dependencies>
        <dependency>
            <groupId>me.ahoo.simba</groupId>
            <artifactId>simba-spring-boot-starter</artifactId>
            <version>${simba.version}</version>
        </dependency>
    </dependencies>
    
</project>
```

The starter provides Simba auto-configuration only. Add exactly one complete backend dependency set below; the backend infrastructure is still required.

### application.yaml

```yaml
simba:
  # Required only when more than one backend module is on the classpath.
  backend: jdbc

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/simba_db
    username: root
    password: root
```

### Optional-1: JdbcMutexContendService

![JdbcMutexContendService](docs/JdbcMutexContendService.png)

> Kotlin DSL

``` kotlin
    implementation("me.ahoo.simba:simba-jdbc:${simbaVersion}")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("com.mysql:mysql-connector-j")
```

> Maven

```xml
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-jdbc</artifactId>
    <version>${simba.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>
```

```sql
create table simba_mutex
(
    mutex             varchar(66)     not null primary key comment 'mutex name',
    acquired_at       bigint unsigned not null,
    ttl_at         bigint unsigned not null,
    transition_at bigint unsigned not null,
    owner_id          varchar(128)    not null,
    version           int unsigned    not null,
    fencing_token     bigint unsigned not null default 0
);
```

### Optional-2: RedisMutexContendService

> Kotlin DSL

``` kotlin
    implementation("me.ahoo.simba:simba-spring-redis:${simbaVersion}")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

> Maven

```xml
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-redis</artifactId>
    <version>${simba.version}</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

### Optional-3: ZookeeperMutexContendService

> Kotlin DSL

``` kotlin
    implementation("me.ahoo.simba:simba-zookeeper:${simbaVersion}")
```

> Maven

```xml
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-zookeeper</artifactId>
    <version>${simba.version}</version>
</dependency>
```

Also register a managed `CuratorFramework` bean as shown in the [Quick Start](https://simba.ahoo.me/guide/quick-start.html).

## Examples

[Simba-Examples](https://github.com/Ahoo-Wang/Simba/tree/main/simba-example)

## Usage

### MutexContender

```java
        MutexContendService contendService = contendServiceFactory.createMutexContendService(new AbstractMutexContender(mutex) {
            @Override
            public void onAcquired(MutexState mutexState) {
                    log.info("onAcquired");
            }
            
            @Override
            public void onReleased(MutexState mutexState) {
                    log.info("onReleased");
            }
        });
        contendService.start();
        try {
            // Use the mutex-protected service.
        } finally {
            contendService.stop();
        }
```

### SimbaLocker

```java
        try (Locker locker = new SimbaLocker("mutex-locker", this.mutexContendServiceFactory)) {
            locker.acquire(Duration.ofSeconds(1));
        /**
         * doSomething
         */
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
```

### Scheduler

Run a method on the leader only — the Spring Boot starter starts it after refresh and stops it on shutdown:

```java
@Service
public class ReportJobs {
    @SimbaScheduled(mutex = "report", fixedDelay = "10s")
    public void generate(ScheduleContext context) {
        reportService.generate(context.getFencingToken());
    }
}
```

Without Spring, use `SimbaScheduler` directly:

```kotlin
SimbaScheduler("report", factory, ScheduleConfig.delay(Duration.ZERO, Duration.ofSeconds(10))) { context ->
    reportService.generate(context.fencingToken)
}.start()
```

Work starts when the node becomes leader and is interrupted when it loses leadership.

### Fencing Tokens

A lease bounds how long the backend grants ownership, not how long a paused owner keeps acting. Pass the fencing
token of the current term to the resource you protect, and let it reject tokens lower than the highest it has seen:

```kotlin
locker.acquire()
repository.save(order, fencingToken = locker.fencingToken)
```

Tokens increase strictly per ownership term on every backend (`0` means none). Redis needs persistence of its counter
(AOF) to stay monotonic across restarts.

### Metrics

With Micrometer (e.g. `spring-boot-starter-actuator`), the starter records `simba.mutex.owner`,
`simba.mutex.ownership.changes`, `simba.mutex.contend`, `simba.mutex.lease.expired` and `simba.scheduler.work`,
tagged by mutex. See [Observability](https://simba.ahoo.me/guide/observability) for alert rules and custom
`ContendObserver`s.

## Upgrading to 4.0

- **Redis:** every node must run Simba 3.2 or later before you roll out 4.0.
- **JDBC:** fencing tokens are on by default. Run
  [`upgrade-simba-mysql-fencing-token.sql`](simba-jdbc/src/init-script/upgrade-simba-mysql-fencing-token.sql) on
  existing tables, or set `simba.jdbc.fencing=false`.
- **Spring Boot:** with more than one backend module on the classpath, set `simba.backend`.
- **API:** `MutexOwner` is a final value (no subclassing, `MutexOwnerEntity` removed, `isInTransition` folded into
  `hasOwner()`); `MutexRetrievalServiceFactory` is gone; `MutexOwnerRepository` keeps only `acquireAndGetOwner` and
  `release`. `simba-core` no longer brings Guava or cosid.

See [ADR 0003](docs/adr/0003-simba-4.md) for the rationale.

#### Use Cases

- [Govern-EventBus](https://github.com/Ahoo-Wang/govern-eventbus/tree/master/eventbus-core/src/main/java/me/ahoo/eventbus/core/compensate)
- [CoSky](https://github.com/Ahoo-Wang/CoSky/blob/main/cosky-rest-api/src/main/kotlin/me/ahoo/cosky/rest/stat/StatServiceScheduler.kt)

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for the workflow, quality gates and release process, and [SECURITY.md](SECURITY.md) to report vulnerabilities.
