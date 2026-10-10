# Simba

[![License](https://img.shields.io/badge/license-Apache%202-4EB1BA.svg)](https://www.apache.org/licenses/LICENSE-2.0.html)
[![GitHub release](https://img.shields.io/github/release/Ahoo-Wang/Simba.svg)](https://github.com/Ahoo-Wang/Simba/releases)
[![Maven Central Version](https://img.shields.io/maven-central/v/me.ahoo.simba/simba-core)](https://central.sonatype.com/artifact/me.ahoo.simba/simba-core)
[![Codacy Badge](https://app.codacy.com/project/badge/Grade/41f28a111d9c457ab7a9aae6861185eb)](https://www.codacy.com/gh/Ahoo-Wang/Simba/dashboard?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=Ahoo-Wang/Simba&amp;utm_campaign=Badge_Grade)
[![codecov](https://codecov.io/gh/Ahoo-Wang/Simba/branch/main/graph/badge.svg?token=P9EMJKJ2I5)](https://codecov.io/gh/Ahoo-Wang/Simba)
[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/Ahoo-Wang/Simba)

> [中文文档](https://simba.ahoo.me/zh/) | [English Documentation](https://simba.ahoo.me/) | [English README](README.md)

Simba 是一个用于 **选主与分布式互斥** 的 JVM 库。应用的各个实例竞争一个具名 mutex；MySQL（JDBC）、Redis 或 Zookeeper
把一个有时限的租约授予其中一个实例，Simba 通过有序回调告诉每个实例它何时获得或失去所有权。无需运行 Simba 服务端。

- **每个租约最多一个本地持有者。** 租约结束且未续期成功时，节点会自行撤销所有权，即使后端调用仍然挂起。
- **Fencing token。** 每个所有权任期都带有严格递增的 token。被 GC 暂停的进程可能在租约结束后恢复；必须拒绝这类过期持有者的资源需要检查
  token。Simba 无法替你完成这一步。
- **三种 API**，基于同一个竞争服务：`@SimbaScheduled` / `SimbaScheduler`（仅在 leader 上执行）、`SimbaLocker`（阻塞锁）、
  `MutexContender`（回调）。

保护任何绝不能重复写入的资源之前，请先阅读 [正确性](https://simba.ahoo.me/zh/guide/correctness)。

## 安装

添加 Spring Boot starter 和 **一个** 后端（Java 17+）：

```kotlin
implementation("me.ahoo.simba:simba-spring-boot-starter:${simbaVersion}")

// JDBC（MySQL）：还需执行一次 simba-jdbc/src/init-script/init-simba-mysql.sql
implementation("me.ahoo.simba:simba-jdbc:${simbaVersion}")
implementation("org.springframework.boot:spring-boot-starter-jdbc")
runtimeOnly("com.mysql:mysql-connector-j")

// 或 Redis
implementation("me.ahoo.simba:simba-spring-redis:${simbaVersion}")
implementation("org.springframework.boot:spring-boot-starter-data-redis")

// 或 Zookeeper：还需定义一个已启动的 CuratorFramework bean
implementation("me.ahoo.simba:simba-zookeeper:${simbaVersion}")
```

Starter 会为基础设施 bean（`DataSource`、`StringRedisTemplate` 或 `CuratorFramework`）存在的那个后端进行配置。classpath 上有多个后端模块时，
请设置 `simba.backend=jdbc|redis|zookeeper`。Maven 坐标、JDBC 表结构以及不使用 Spring 的用法见
[快速开始](https://simba.ahoo.me/zh/guide/quick-start)。

## 使用

### 仅在 leader 上调度

```java
@Service
public class ReportJobs {
    @SimbaScheduled(mutex = "report", fixedDelay = "1m")
    public void generate(ScheduleContext context) {
        reportService.generate(context.getFencingToken());
    }
}
```

该方法只在 leader 上运行；节点失去领导权时会被中断。不使用 Spring 时，使用
`SimbaScheduler(mutex, factory, ScheduleConfig.delay(...)) { context -> ... }`。

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
// 关闭时
contendService.stop();
```

## 文档

| | |
|---|---|
| [简介](https://simba.ahoo.me/zh/guide/) | 保证、如何选择 API 和后端 |
| [后端](https://simba.ahoo.me/zh/guide/backends) | JDBC、Redis、Zookeeper：存储、部署、运维要点 |
| [正确性](https://simba.ahoo.me/zh/guide/correctness) | 租约、ttl/transition、fencing token、故障模式 |
| [配置](https://simba.ahoo.me/zh/guide/configuration) | `simba.*` 属性与工厂 |
| [可观测性](https://simba.ahoo.me/zh/guide/observability) | Micrometer 指标、`simba` Actuator 端点、告警 |
| [升级](https://simba.ahoo.me/zh/guide/upgrading) | 升级到 4.x |
| [API 参考](https://simba.ahoo.me/zh/api/) | 公共类型 |
| [架构](https://simba.ahoo.me/zh/architecture/)、[ADR](docs/adr/) | 内部实现与设计决策 |

[示例应用](simba-example) 可以在任意后端上运行。

## 使用者

- [Govern-EventBus](https://github.com/Ahoo-Wang/govern-eventbus/tree/master/eventbus-core/src/main/java/me/ahoo/eventbus/core/compensate)
- [CoSky](https://github.com/Ahoo-Wang/CoSky/blob/main/cosky-rest-api/src/main/kotlin/me/ahoo/cosky/rest/stat/StatServiceScheduler.kt)

## 参与贡献

工作流程、质量门禁和发布流程参见 [CONTRIBUTING.md](CONTRIBUTING.md)，测试与后端 TCK 参见
[贡献者指南](https://simba.ahoo.me/zh/contributing/)，报告安全漏洞参见 [SECURITY.md](SECURITY.md)。
