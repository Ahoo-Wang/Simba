---
title: 快速开始
description: 把 Simba 加入 Spring Boot 或普通 JVM 应用，接入一个后端，并运行选举、leader 任务、锁和分区组。
---

# 快速开始

Simba 5 需要 Java 21 或更高版本（任务和后端调用运行在虚拟线程上）。starter 需要 Spring Boot 4（4.1 或更高版本）；
各库按 Kotlin 2.2 或更高版本编译。

## 依赖 {#dependencies}

添加 Spring Boot starter 和 **一个** 后端模块。

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

`simba-bom` 统一所有 Simba 模块的版本。不使用 Spring 时，依赖 `simba-core` 加一个后端模块：`simba-jdbc`、
`simba-zookeeper`，或者 Redis 用 `simba-spring-redis`（它会带上 `simba-redis`）。单独的 `simba-redis` 只定义了
`RedisScripting` 端口：只有在你为其他 Redis 客户端自行实现适配器时，才单独使用它。

## 后端准备 {#backend-setup}

| 后端 | 你需要提供 | 说明 |
|---|---|---|
| JDBC | 一个 `DataSource` bean 和三张表 | 执行一次 [`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-mysql.sql) 或 [`init-simba-postgresql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-postgresql.sql)；方言根据 `DataSource` 自动识别 |
| Redis | 一个 `RedisConnectionFactory` bean（Spring Boot 根据 `spring.data.redis.*` 创建） | Redis 7；单机、Sentinel 或 Cluster |
| Zookeeper | `simba.zookeeper.connect-string` | Simba 自行打开 Zookeeper 客户端，每个会话一个 |

这些脚本位于 `simba-jdbc` 的 classpath 上，因此 Spring Boot 可以替你执行：

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:me/ahoo/simba/jdbc/init-simba-mysql.sql
```

当 classpath 上有多个后端模块时，请设置 `simba.backend=jdbc|redis|zookeeper`，否则启动会失败。
[后端](/zh/guide/backends) 页面列出了每个后端的表结构、键和运维说明。

starter 会创建一个 `Simba` bean。在需要使用下面任一 API 的地方注入它即可。

## 选举 {#election}

基础 API：本节点获得和失去任期时的回调。

```kotlin
val election = simba.election("order-dispatcher")
election.start(object : LeadershipListener {
    override fun onElected(term: Term) = dispatcher.start(term.token)
    override fun onRevoked(term: Term, reason: RevokeReason) = dispatcher.stop()
})

// on shutdown: completes after the last onRevoked ran
election.stop().toCompletableFuture().join()
```

每个 `onElected(term)` 之后恰好跟随一个 `onRevoked(term, reason)`。保持回调简短；同一选举的回调逐个执行。

## Leader 任务 {#leader-work}

通常你希望代码在本节点担任 leader *期间* 运行。`runWhileLeader` 每个任期运行一次代码块，运行在它自己的虚拟线程上，并在任期结束时中断它：

```kotlin
val task = simba.election("report").runWhileLeader { term ->
    while (term.isValid) {
        reportStore.writeChunk(nextChunk(), fencingToken = term.token)
    }
}
// later
task.close()
```

只在 leader 上运行的周期性任务：

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

调度、交接和分区组详见 [Leader 任务与分区](/zh/guide/leader-work)。

## 锁 {#lock}

`acquire` 返回一个 `LockTerm`：锁的凭证。关闭它即释放租约。

```kotlin
simba.lock("invoice-42").acquire(Duration.ofSeconds(5)).use { term ->
    term.ensureValid(Duration.ofSeconds(2))      // enough time left for this step?
    invoiceStore.write(invoice, fencingToken = term.token)
}
```

如果在超时之前没有赢得任期，`acquire` 抛出 `LockTimeoutException`；`tryAcquire()` 只尝试一次，失败时返回 `null`。
详见 [锁](/zh/guide/locks)。

## 分区组 {#partition-group}

把分区分散到存活的节点上；每个分区同一时刻只在一个节点上运行。

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

## 不使用 Spring {#without-spring}

基于一个后端自行构建客户端；`Simba` 实现了 `AutoCloseable`，关闭它会立即释放所有租约。

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

每个进程只创建一个 `Simba`：所有选举、锁和组共享它的会话，因此一次续约 (keep-alive) 就能为它们全部续期。
[Spring Boot](/zh/guide/configuration#without-spring) 页面列出了 builder 的选项。

## 运行示例 {#run-the-example}

[示例应用](https://github.com/Ahoo-Wang/Simba/tree/main/simba-example) 针对你选择的后端使用了 `@SimbaScheduled`、
`@SimbaPartitioned` 和一个选举：

```bash
./gradlew simba-example:run -PexampleBackend=redis   # jdbc | redis | zookeeper
```

启动两个实例，再停止其中一个，观察另一个接管。
