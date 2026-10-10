---
title: 快速开始
description: 在 Spring Boot 或普通 JVM 应用中引入 Simba，配置一个后端，并运行仅在 leader 上执行的工作。
---

# 快速开始

要求：JDK 17+，以及一个运行中的 MySQL、Redis 或 Zookeeper。示例使用 Spring Boot 4.1 及其依赖管理；不使用 Spring Boot 时请参阅
[不使用 Spring](#不使用-spring)。

## 1. 添加依赖

Starter 只包含自动配置。将它与 **恰好一组** 后端依赖一起添加：

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

如果你更喜欢以 platform 方式导入，`simba-bom`（`me.ahoo.simba:simba-bom`）可以统一所有 Simba 模块的版本。

## 2. 连接后端

当后端的基础设施 bean 存在时，starter 会创建 `MutexContendServiceFactory` bean：`DataSource`、`StringRedisTemplate`
或 `CuratorFramework`。

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

JDBC 还需要 `simba_mutex` 表：执行一次
[`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/init-script/init-simba-mysql.sql)。
新 mutex 的行会自动插入。表结构以及 Redis、Zookeeper 的运维要点见 [后端](/zh/guide/backends)。

租约时序默认为 `ttl=10s`、`transition=6s`；修改之前请先阅读 [配置](/zh/guide/configuration)。

## 3. 在 leader 上运行代码

### 仅在 leader 上调度

给 bean 方法加注解。它只在 leader 上运行，在上下文刷新后启动，在关闭时停止：

```kotlin
@Service
class ReportJobs(private val reports: ReportService) {
    @SimbaScheduled(mutex = "report", fixedDelay = "1m")
    fun generate(context: ScheduleContext) {
        reports.generate(fencingToken = context.fencingToken)
    }
}
```

`fixedDelay` 和 `fixedRate` 必须且只能设置一个；`initialDelay` 默认为 `0s`。方法不接受参数，或只接受一个 `ScheduleContext`。
时长使用 Spring Boot 格式（`10s`、`500ms`、`PT1M`），并支持 `${...}` 占位符。工作在独立线程上运行，节点失去领导权时会被
**中断**，因此请让它响应中断。

### SimbaLocker

阻塞调用线程直到持有 mutex；`close()` 释放它：

```kotlin
SimbaLocker("nightly-migration", factory).use { locker ->
    locker.acquire(Duration.ofSeconds(30)) // 超时抛出 TimeoutException
    migrate(fencingToken = locker.fencingToken)
}
```

```java
try (Locker locker = new SimbaLocker("nightly-migration", factory)) {
    locker.acquire(Duration.ofSeconds(30));
    migrate(locker.getFencingToken());
}
```

一个 locker 同一时间只属于一个线程。中断线程不会取消 `acquire()`（中断标志会被恢复）；请使用带超时的重载来限制等待时间。

### MutexContender

在服务运行期间持续接收回调：

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

回调在 `simbaHandleExecutor` 上运行，每个竞争者同一时间只运行一个回调。保持回调简短，把耗时工作交给其他线程。
`stop()` 会等待它自己的 `onReleased` 执行完毕，因此不要在持有回调也会获取的锁时调用它。

## 不使用 Spring

为后端构建工厂，并自行管理生命周期：

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
// 关闭时
scheduler.close()
factory.close()
```

Redis 和 Zookeeper 的工厂及其执行器见 [配置](/zh/guide/configuration#不使用-spring)。

## 示例应用

[`simba-example`](https://github.com/Ahoo-Wang/Simba/tree/main/simba-example) 是一个 Spring Boot 应用，包含一个竞争者和一个
`@SimbaScheduled` 任务；可以在任意后端上运行（`-PexampleBackend=jdbc|redis|zookeeper`）。
