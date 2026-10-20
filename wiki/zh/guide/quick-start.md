---
title: 快速开始
description: 把 Simba 加入 Spring Boot 或普通 JVM 应用，接入一个后端，并运行一个仅在 leader 上执行的任务、Leader 任务、锁、分区组和裸选举。
---

# 快速开始

从零开始，先得到一个恰好只在一个节点上运行的任务，再按控制粒度由粗到细介绍其他 API。

**要求：** Java 21 或更高版本（任务和后端调用运行在虚拟线程上）。starter 需要 Spring Boot 4.1 或更高版本；
各库按 Kotlin 2.2 或更高版本编译。

## 1. 添加依赖 {#_1-add-the-dependencies}

Spring Boot starter 加 **一个** 后端模块：

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

`simba-bom` 统一所有 Simba 模块的版本。不使用 Spring 时，依赖 `simba-core` 加一个后端模块：`simba-jdbc`、`simba-zookeeper`，
或者 Redis 用 `simba-spring-redis`（它会带上 `simba-redis`）。单独的 `simba-redis` 只定义了 `RedisScripting` 端口；
只有在你为另一个 Redis 客户端编写了自己的适配器时，才脱离 `simba-spring-redis` 单独使用它。

## 2. 接入后端 {#_2-point-it-at-a-backend}

| 后端 | 你需要提供 |
|---|---|
| JDBC | 一个 `DataSource` bean，以及用 [`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-mysql.sql) 或 [`init-simba-postgresql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-postgresql.sql) 一次性创建的三张表；方言根据 `DataSource` 自动识别 |
| Redis | 一个 `RedisConnectionFactory` bean（Spring Boot 根据 `spring.data.redis.*` 创建）；支持单机、Sentinel 或 Cluster |
| Zookeeper | `simba.zookeeper.connect-string`；Simba 自行打开 Zookeeper 客户端，每个会话一个 |

JDBC 脚本位于 `simba-jdbc` 的类路径上，因此可以让 Spring Boot 替你执行：

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:me/ahoo/simba/jdbc/init-simba-mysql.sql
```

类路径上有多个后端模块时，需设置 `simba.backend=jdbc|redis|zookeeper`，否则启动失败。此时 starter 会创建一个 `Simba` bean；
在下面用到 API 的地方注入它即可。各后端的存储布局和运维说明见 [后端](/zh/guide/backends)。

## 3. 在一个节点上运行任务 {#_3-run-a-job-on-one-node}

最常见的需求：一个绝不能同时在两个节点上运行的周期性任务。

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

启动两个实例：一个成为 `report` 的 leader 并运行任务，另一个等待。停止 leader，另一个就会接管。
任务收到的 `Term` 携带 fencing token；把它传给任务所写入的任何资源（见 [Fencing 与故障](/zh/guide/correctness)）。

## 4. 其他 API {#_4-the-other-apis}

### 担任 leader 期间工作 {#work-while-leading}

`runWhileLeader` 每个任期在独立的虚拟线程上运行一次代码块，并在任期结束时中断它：

```kotlin
val task = simba.election("ingest").runWhileLeader { term ->
    while (term.isValid) {
        sink.write(source.poll(), fencingToken = term.token)
    }
}
// later: stops the election, waits for the block to exit, then releases the lease
task.close()
```

### 把分区分散到存活的节点上 {#partitions-over-the-live-nodes}

16 个分区中的每一个同一时刻恰好在一个节点上运行；节点加入或离开时，只移动它那一份：

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

### 在某个步骤外加锁 {#a-lock-around-one-step}

`acquire` 返回一个 `LockTerm`；关闭它即释放租约：

```kotlin
simba.lock("invoice-42").acquire(Duration.ofSeconds(5)).use { term ->
    term.ensureValid(Duration.ofSeconds(2))      // enough time left for this step?
    invoiceStore.write(invoice, fencingToken = term.token)
}
```

如果在时限内没有赢得任期，`acquire` 抛出 `LockTimeoutException`；`tryAcquire()` 只尝试一次，失败时返回 `null`。

### 裸选举 {#a-bare-election}

当你自己管理任务时，监听任期：

```kotlin
val election = simba.election("order-dispatcher")
election.start(object : LeadershipListener {
    override fun onElected(term: Term) = dispatcher.start(term.token)
    override fun onRevoked(term: Term, reason: RevokeReason) = dispatcher.stop()
})

// on shutdown: completes after the last onRevoked ran
election.stop().toCompletableFuture().join()
```

每个 `onElected(term)` 之后恰好跟随一个 `onRevoked(term, reason)`。回调要保持简短：同一选举的回调逐个执行。

各 API 的细节见 [Leader 任务](/zh/guide/leader-work) 和 [锁](/zh/guide/locks)。

## 不使用 Spring {#without-spring}

自己在后端之上构建客户端。`Simba` 实现了 `AutoCloseable`；关闭它会立即释放所有租约。

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

每个进程只创建 **一个** `Simba`：所有选举、锁和分区组共享它的会话，因此一次续约即可为它们全部续期。
构建器选项列在 [不使用 Spring](/zh/guide/configuration#without-spring) 中。

## 运行示例 {#run-the-example}

[示例应用](https://github.com/Ahoo-Wang/Simba/tree/main/simba-example) 在你选择的后端上使用 `@SimbaScheduled`、
`@SimbaPartitioned` 和一个选举：

```bash
./gradlew simba-example:run -PexampleBackend=redis
```

`-PexampleBackend` 可取 `jdbc`、`redis` 或 `zookeeper`。启动两次，然后停止其中一个实例，观察另一个接管。
