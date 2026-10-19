# Simba

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/Simba.svg)](https://github.com/Ahoo-Wang/Simba/releases)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.simba/simba-core)](https://central.sonatype.com/artifact/me.ahoo.simba/simba-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/41f28a111d9c457ab7a9aae6861185eb)](https://www.codacy.com/gh/Ahoo-Wang/Simba/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Ahoo-Wang/Simba&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/Simba/branch/main/graph/badge.svg?token=P9EMJKJ2I5)](https://codecov.io/gh/Ahoo-Wang/Simba)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/Simba)

> [中文文档](https://simba.ahoo.me/zh/) | [English Documentation](https://simba.ahoo.me/) | [English README](README.md)

Simba 是一个用于 **选主与分布式锁** 的 JVM 库。每个进程在你已经在运维的后端上打开一个会话——MySQL 8 或 PostgreSQL 14+
（JDBC）、Redis 7 或 Zookeeper——并在其上竞争具名租约。赢得租约的节点得到一个 `Term`（任期）：它携带 fencing token，
并知道自己还能有效多久。无需运行 Simba 服务端。

- **每个名字至多一个有效任期**（就本地时钟所能判断的范围而言）：本地截止时刻从每次续约 *发出* 的时刻算起，总是早于后端
  把租约交给别人的时刻。
- **Fencing token。** 每个任期都带有按名字严格递增的 token。被 GC 暂停的进程可能在任期结束后恢复；必须拒绝这类过期持有者
  的资源需要校验 token。Simba 无法替你完成这一步，也不承诺"无脑裂"。
- **每个节点一个会话。** 一次续约就续上进程内所有的选举、锁和分区。
- **任务绑定任期。** Leader 任务运行在虚拟线程上，任期结束时被中断，并在释放租约之前结束。

保护任何绝不能重复写入的资源之前，请先阅读 [核心概念与正确性](https://simba.ahoo.me/zh/guide/correctness)。
从 4.x 升级？请看 [迁移到 5.0](https://simba.ahoo.me/zh/guide/migration)。

## 安装

需要 Java 21+；starter 需要 Spring Boot 4（4.1+）。添加 Spring Boot starter 和 **一个** 后端：

```kotlin
implementation("me.ahoo.simba:simba-spring-boot-starter:${simbaVersion}")

// JDBC（MySQL 8 或 PostgreSQL 14+）：还需用 simba-jdbc 中的
// me/ahoo/simba/jdbc/init-simba-mysql.sql 或 init-simba-postgresql.sql 建表一次
implementation("me.ahoo.simba:simba-jdbc:${simbaVersion}")
implementation("org.springframework.boot:spring-boot-starter-jdbc")
runtimeOnly("com.mysql:mysql-connector-j") // 或 org.postgresql:postgresql

// 或 Redis 7
implementation("me.ahoo.simba:simba-spring-redis:${simbaVersion}")
implementation("org.springframework.boot:spring-boot-starter-data-redis")

// 或 Zookeeper：设置 simba.zookeeper.connect-string
implementation("me.ahoo.simba:simba-zookeeper:${simbaVersion}")
```

starter 会基于类路径上的后端创建一个 `Simba` bean。类路径上有多个后端模块时，设置 `simba.backend=jdbc|redis|zookeeper`。
不使用 Spring：

```kotlin
val simba = Simba.builder()
    .backend(JdbcLeaseBackend.postgres(dataSource))   // 或 RedisLeaseBackend / ZookeeperLeaseBackend
    .build()                                          // AutoCloseable：close() 一次性释放全部租约
```

## 使用

### 选举

```kotlin
simba.election("order-dispatcher").start(object : LeadershipListener {
    override fun onElected(term: Term) = dispatcher.start(term.token)
    override fun onRevoked(term: Term, reason: RevokeReason) = dispatcher.stop()
})
```

每个 `onElected(term)` 之后恰好有一个 `onRevoked(term, reason)`。

### Leader 任务

```kotlin
simba.election("ingest").runWhileLeader { term ->
    while (term.isValid) sink.write(source.next(), fencingToken = term.token)
}
```

代码块每个任期运行一次，任期结束时被中断；`stop()` 会等它结束后再释放租约。

### 仅在 leader 上调度

```java
@Service
public class ReportJobs {
    @SimbaScheduled(name = "report", fixedDelay = "1m")
    public void generate(Term term) {
        reportService.generate(term.getToken());
    }
}
```

不使用 Spring：`simba.election("report").schedule(Schedule.fixedDelay(Duration.ofMinutes(1))) { term -> ... }`。

### 分区

```java
@SimbaPartitioned(group = "billing", partitions = 16, fixedDelay = "1m")
public void bill(Term term, int partition) {
    billing.run(partition, term.getToken());
}
```

16 个分区中的每一个同一时刻只在一个存活节点上运行。不使用 Spring：`simba.group("billing", 16).schedule(...)`。

### 锁

```kotlin
simba.lock("invoice-42").acquire(Duration.ofSeconds(5)).use { term ->
    term.ensureValid(Duration.ofSeconds(2))
    invoiceStore.write(invoice, fencingToken = term.token)
}
```

返回的 `LockTerm` 就是凭证：关闭它即释放租约。

### 可观测性

`simba.snapshot()` 展示本节点的会话与每个选举；starter 额外提供 Micrometer 指标、`simba` Actuator 端点和 `simba` 健康检查。

### 测试

`simba-testing` 为你的单元测试提供内存后端和虚拟时间；`simba-tck` 提供 `FencingSpec`（验证你校验 token 的资源）和
`LeaseBackendSpec`（验证后端）。

## 文档

| | |
|---|---|
| [简介](https://simba.ahoo.me/zh/guide/) | 保证什么，如何选择 API 和后端 |
| [快速开始](https://simba.ahoo.me/zh/guide/quick-start) | 依赖、后端配置、每个 API 的第一段代码 |
| [核心概念与正确性](https://simba.ahoo.me/zh/guide/correctness) | 会话、租约、任期、fencing token、交接、故障模式 |
| [后端](https://simba.ahoo.me/zh/guide/backends) | JDBC、Redis、Zookeeper：表结构、key、路径、运维要点 |
| [Spring Boot](https://simba.ahoo.me/zh/guide/configuration) | `simba.*` 属性、bean、关闭顺序 |
| [Leader 任务与分区](https://simba.ahoo.me/zh/guide/leader-work) | `runWhileLeader`、调度、分区组、注解 |
| [锁](https://simba.ahoo.me/zh/guide/locks) | `lock(name)` 与 `LockTerm` |
| [可观测性](https://simba.ahoo.me/zh/guide/observability) | 快照、事件、指标、Actuator 端点、健康检查 |
| [测试](https://simba.ahoo.me/zh/guide/testing) | 内存后端、虚拟时间、TCK |
| [从 4.x 迁移](https://simba.ahoo.me/zh/guide/migration) | API、配置项与存储对照 |
| [API 参考](https://simba.ahoo.me/zh/api/) | 公共类型 |
| [架构](https://simba.ahoo.me/zh/architecture/)、[ADR](docs/adr/) | 内部实现与设计决策 |

[示例应用](simba-example) 可以在任一后端上运行。

## 谁在使用

- [Govern-EventBus](https://github.com/Ahoo-Wang/govern-eventbus/tree/master/eventbus-core/src/main/java/me/ahoo/eventbus/core/compensate)
- [CoSky](https://github.com/Ahoo-Wang/CoSky/blob/main/cosky-rest-api/src/main/kotlin/me/ahoo/cosky/rest/stat/StatServiceScheduler.kt)

## 参与贡献

工作流程、质量门禁和发布流程见 [CONTRIBUTING.md](CONTRIBUTING.md)，测试与后端 TCK 见
[贡献者指南](https://simba.ahoo.me/zh/contributing/)，漏洞报告见 [SECURITY.md](SECURITY.md)。
