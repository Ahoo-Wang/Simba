---
title: Quick Start
description: Add Simba to a Spring Boot or plain JVM application, configure one backend, and run leader-only work.
---

# Quick Start

Requirements: JDK 17+, and a running MySQL, Redis or Zookeeper. The examples use Spring Boot 4.1 with its dependency
management; without Spring Boot, see [Without Spring](#without-spring).

## 1. Add Dependencies

The starter only contains auto-configuration. Add it together with **exactly one** backend set:

::: code-group

```kotlin [JDBC (Gradle)]
implementation("me.ahoo.simba:simba-spring-boot-starter:4.3.0")
implementation("me.ahoo.simba:simba-jdbc:4.3.0")
implementation("org.springframework.boot:spring-boot-starter-jdbc")
runtimeOnly("com.mysql:mysql-connector-j")
```

```kotlin [Redis (Gradle)]
implementation("me.ahoo.simba:simba-spring-boot-starter:4.3.0")
implementation("me.ahoo.simba:simba-spring-redis:4.3.0")
implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

```kotlin [Zookeeper (Gradle)]
implementation("me.ahoo.simba:simba-spring-boot-starter:4.3.0")
implementation("me.ahoo.simba:simba-zookeeper:4.3.0")
```

```xml [JDBC (Maven)]
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-boot-starter</artifactId>
    <version>4.3.0</version>
</dependency>
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-jdbc</artifactId>
    <version>4.3.0</version>
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

```xml [Redis (Maven)]
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-boot-starter</artifactId>
    <version>4.3.0</version>
</dependency>
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-redis</artifactId>
    <version>4.3.0</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```xml [Zookeeper (Maven)]
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-boot-starter</artifactId>
    <version>4.3.0</version>
</dependency>
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-zookeeper</artifactId>
    <version>4.3.0</version>
</dependency>
```

:::

`simba-bom` (`me.ahoo.simba:simba-bom`) aligns the versions of all Simba modules if you prefer a platform import.

## 2. Connect the Backend

The starter creates a `MutexContendServiceFactory` bean once the backend's infrastructure bean exists: a
`DataSource`, a `StringRedisTemplate`, or a `CuratorFramework`.

::: code-group

```yaml [JDBC]
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/simba_db
    username: simba
    password: ${DB_PASSWORD}
```

```yaml [Redis]
spring:
  data:
    redis:
      url: redis://localhost:6379
```

```kotlin [Zookeeper]
@Configuration(proxyBeanMethods = false)
class ZookeeperConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun curatorFramework(): CuratorFramework =
        CuratorFrameworkFactory.newClient("localhost:2181", ExponentialBackoffRetry(1000, 3))
}
```

:::

JDBC also needs the `simba_mutex` table: run
[`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/init-script/init-simba-mysql.sql)
once. Rows for new mutexes are inserted automatically. See [Backends](/guide/backends) for the schema and for Redis
and Zookeeper operating notes.

Lease timing defaults to `ttl=10s`, `transition=6s`; see [Configuration](/guide/configuration) before changing it.

## 3. Run Code on the Leader

### Leader-Only Scheduling

Annotate a bean method. It runs on the leader only, starts after the context refreshes and stops on shutdown:

```kotlin
@Service
class ReportJobs(private val reports: ReportService) {
    @SimbaScheduled(mutex = "report", fixedDelay = "1m")
    fun generate(context: ScheduleContext) {
        reports.generate(fencingToken = context.fencingToken)
    }
}
```

Set exactly one of `fixedDelay` and `fixedRate`; `initialDelay` defaults to `0s`. The method takes no parameter or a
single `ScheduleContext`. Durations use Spring Boot formats (`10s`, `500ms`, `PT1M`) and accept `${...}` placeholders.
The work runs on its own thread and is **interrupted** when the node loses leadership, so make it respond to
interruption.

### SimbaLocker

Block the calling thread until it owns the mutex; `close()` releases it:

```kotlin
SimbaLocker("nightly-migration", factory).use { locker ->
    locker.acquire(Duration.ofSeconds(30)) // throws TimeoutException
    migrate(fencingToken = locker.fencingToken)
}
```

```java
try (Locker locker = new SimbaLocker("nightly-migration", factory)) {
    locker.acquire(Duration.ofSeconds(30));
    migrate(locker.getFencingToken());
}
```

A locker belongs to one thread at a time. Interrupting the thread does not cancel `acquire()` (the interrupt flag is
restored); use the timeout overload to bound the wait.

### MutexContender

Receive callbacks for as long as the service runs:

```kotlin
@Component
class Coordinator(factory: MutexContendServiceFactory) : AbstractMutexContender("coordinator"), SmartLifecycle {
    private val service = factory.createMutexContendService(this)

    override fun onAcquired(mutexState: MutexState) = startCoordinating()
    override fun onReleased(mutexState: MutexState) = stopCoordinating()

    override fun start() = service.start()
    override fun stop() = service.stop()
    override fun isRunning() = service.running
}
```

Callbacks run on the `simbaHandleExecutor`, one at a time per contender. Keep them short and hand long work to
another thread. `stop()` waits for its own `onReleased`, so do not call it while holding a lock that your callbacks
take.

## Without Spring

Build a factory for your backend and manage lifecycles yourself:

```kotlin
val factory = JdbcMutexContendServiceFactory(
    mutexOwnerRepository = JdbcMutexOwnerRepository(dataSource, queryTimeout = Duration.ofSeconds(10)),
    initialDelay = Duration.ZERO,
    ttl = Duration.ofSeconds(10),
    transition = Duration.ofSeconds(6)
)

val scheduler = SimbaScheduler("report", factory, ScheduleConfig.delay(Duration.ZERO, Duration.ofMinutes(1))) { context ->
    reports.generate(fencingToken = context.fencingToken)
}
scheduler.start()
// on shutdown
scheduler.close()
factory.close()
```

[Configuration](/guide/configuration#without-spring) lists the Redis and Zookeeper factories and their executors.

## Example Application

[`simba-example`](https://github.com/Ahoo-Wang/Simba/tree/main/simba-example) is a Spring Boot application with a
contender and a `@SimbaScheduled` job; it runs against any backend (`-PexampleBackend=jdbc|redis|zookeeper`).
