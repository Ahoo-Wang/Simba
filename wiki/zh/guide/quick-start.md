---
title: 快速开始
description: 几分钟内上手 Simba。添加依赖、选择后端，用几行代码获取你的第一个分布式锁。
---

# 快速开始

本指南将带你完成 Simba 的项目集成、后端配置，以及用几行代码获取分布式锁的全过程。

## 前置条件

- **JDK 17** 或更高版本（Simba 面向 JVM 17 工具链）
- **Gradle 8+**（推荐使用 Kotlin DSL）或 **Maven 3.9+**
- 一个已运行的后端实例：MySQL、Redis 或 Zookeeper

## 添加依赖

以下示例面向 Spring Boot 4.1，并假定已启用其依赖管理。Simba starter 提供自动配置，但每个后端仍需要对应的客户端和基础设施依赖。请选择一套完整组合。

### Gradle Kotlin DSL

::: code-group

```kotlin [JDBC/MySQL]
implementation("me.ahoo.simba:simba-spring-boot-starter:3.1.2")
implementation("me.ahoo.simba:simba-jdbc:3.1.2")
implementation("org.springframework.boot:spring-boot-starter-jdbc")
runtimeOnly("com.mysql:mysql-connector-j")
```

```kotlin [Redis]
implementation("me.ahoo.simba:simba-spring-boot-starter:3.1.2")
implementation("me.ahoo.simba:simba-spring-redis:3.1.2")
implementation("org.springframework.boot:spring-boot-starter-data-redis")
```

```kotlin [Zookeeper]
implementation("me.ahoo.simba:simba-spring-boot-starter:3.1.2")
implementation("me.ahoo.simba:simba-zookeeper:3.1.2")
```

:::

### Maven XML

::: code-group

```xml [JDBC/MySQL]
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-boot-starter</artifactId>
    <version>3.1.2</version>
</dependency>
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-jdbc</artifactId>
    <version>3.1.2</version>
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

```xml [Redis]
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-boot-starter</artifactId>
    <version>3.1.2</version>
</dependency>
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-redis</artifactId>
    <version>3.1.2</version>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```xml [Zookeeper]
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-spring-boot-starter</artifactId>
    <version>3.1.2</version>
</dependency>
<dependency>
    <groupId>me.ahoo.simba</groupId>
    <artifactId>simba-zookeeper</artifactId>
    <version>3.1.2</version>
</dependency>
```

:::

## 选择你的 API 级别

Simba 提供三个 API 级别。根据你的使用场景选择合适的：

```mermaid
graph TD
    subgraph sg_78 ["Which API?"]
        direction TB
        Q1{"Need periodic<br>scheduled work?"}
        Q2{"Want RAII /<br>try-with-resources?"}
        Q3{"Need full control<br>via callbacks?"}
        SCH["Use AbstractScheduler"]
        LK["Use SimbaLocker"]
        CB["Use MutexContender"]
    end

    Q1 -->|"Yes"| SCH
    Q1 -->|"No"| Q2
    Q2 -->|"Yes"| LK
    Q2 -->|"No"| CB

    style Q1 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Q2 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Q3 fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style SCH fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style LK fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CB fill:#2d333b,stroke:#6d5dfc,color:#e6edf3

```

## 使用 MutexContender 的基本用法

使用 Simba 最简单的方式是实现 [`MutexContender`]([file_path:simba-core/src/main/kotlin/me/ahoo/simba/core/MutexContender.kt](https://github.com/Ahoo-Wang/Simba/blob/main/simba-core/src/main/kotlin/me/ahoo/simba/core/MutexContender.kt))。你会在获取或丢失锁时收到回调通知。

```kotlin
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexState

class LeaderContender(mutex: String) : AbstractMutexContender(mutex) {
    override fun onAcquired(mutexState: MutexState) {
        println("[$contenderId] acquired leadership for mutex: $mutex")
    }

    override fun onReleased(mutexState: MutexState) {
        println("[$contenderId] lost leadership for mutex: $mutex")
    }
}
```

创建竞争者并启动竞争：

```kotlin
val factory: MutexContendServiceFactory = /* obtain from backend, e.g. JdbcMutexContendServiceFactory */
val contender = LeaderContender("my-task-lock")
val service = factory.createMutexContendService(contender)
service.start()

// When done:
service.stop()
```

## 使用 SimbaLocker

[`SimbaLocker`]([file_path:simba-core/src/main/kotlin/me/ahoo/simba/locker/SimbaLocker.kt](https://github.com/Ahoo-Wang/Simba/blob/main/simba-core/src/main/kotlin/me/ahoo/simba/locker/SimbaLocker.kt)) 实现了 `AutoCloseable` 接口，因此你可以在 try-with-resources 代码块中使用它。调用线程会阻塞直到获取锁为止。

```kotlin
import me.ahoo.simba.locker.SimbaLocker
import java.time.Duration

val factory: MutexContendServiceFactory = /* ... */

SimbaLocker("my-task-lock", factory).use { locker ->
    locker.acquire()
    println("Lock acquired -- doing critical work")
    // lock is released automatically when the block exits
}

// With a timeout:
SimbaLocker("my-task-lock", factory).use { locker ->
    locker.acquire(Duration.ofSeconds(30))
    println("Lock acquired within 30s")
}
```

## 使用 AbstractScheduler

[`AbstractScheduler`]([file_path:simba-core/src/main/kotlin/me/ahoo/simba/schedule/AbstractScheduler.kt](https://github.com/Ahoo-Wang/Simba/blob/main/simba-core/src/main/kotlin/me/ahoo/simba/schedule/AbstractScheduler.kt)) 非常适合只需在当前领导者实例上执行的周期性任务。它会在领导权变更时自动启动和停止调度工作。

```kotlin
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig
import java.time.Duration

class MyCleanupScheduler(
    contendServiceFactory: MutexContendServiceFactory
) : AbstractScheduler("cleanup-task", contendServiceFactory) {

    override val config: ScheduleConfig = ScheduleConfig.rate(
        initialDelay = Duration.ofSeconds(0),
        period = Duration.ofMinutes(5)
    )

    override val worker: String = "cleanup-worker"

    override fun work() {
        println("Running cleanup on leader instance...")
    }
}

// Start the scheduler
val scheduler = MyCleanupScheduler(factory)
scheduler.start()

// Stop when shutting down
scheduler.stop()
```

## Spring Boot 自动配置

只有在所选后端的基础设施可用后，starter 才会创建 `MutexContendServiceFactory`。请配置对应的 `DataSource`、Redis 连接或 `CuratorFramework` Bean。

::: code-group

```yaml [JDBC application.yml]
simba:
  jdbc:
    enabled: true
    initial-delay: 0s
    ttl: 10s
    transition: 6s

spring:
  datasource:
    url: jdbc:mysql://localhost:3306/simba_db
    username: root
    password: root
```

```yaml [Redis application.yml]
simba:
  redis:
    enabled: true
    ttl: 10s
    transition: 6s

spring:
  data:
    redis:
      url: redis://localhost:6379
```

```kotlin [Zookeeper Bean]
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ZookeeperConfiguration {
    @Bean(initMethod = "start", destroyMethod = "close")
    fun curatorFramework(): CuratorFramework = CuratorFrameworkFactory.newClient(
        "localhost:2181",
        ExponentialBackoffRetry(1000, 3)
    )
}
```

:::

使用 JDBC 时，还需通过 [`simba-jdbc/src/init-script/init-simba-mysql.sql:17`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/init-script/init-simba-mysql.sql#L17) 创建 `simba_mutex` 表。

满足这些前置条件后，自动配置会创建 `MutexContendServiceFactory` Bean。注入它即可直接使用：

```kotlin
import org.springframework.stereotype.Component
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexState
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy

@Component
class MyLeaderTask(
    private val contendServiceFactory: MutexContendServiceFactory
) : AbstractMutexContender("spring-task-lock") {

    private val service = contendServiceFactory.createMutexContendService(this)

    @PostConstruct
    fun onStart() = service.start()

    @PreDestroy
    fun onStop() = service.stop()

    override fun onAcquired(mutexState: MutexState) {
        println("This instance is now the leader!")
    }

    override fun onReleased(mutexState: MutexState) {
        println("Leadership lost.")
    }
}
```

## 锁获取时序图

下图展示了两个竞争者竞争同一互斥锁的完整时序：

```mermaid
sequenceDiagram
autonumber
    participant A as Contender A
    participant S as Backend Storage
    participant B as Contender B

    A->>S: startContend() -- acquire mutex
    S-->>A: success -- owner = A (ttlAt, transitionAt)
    A->>A: onAcquired()
    B->>S: startContend() -- acquire mutex
    S-->>B: fail -- owner = A (within transition)
    B->>B: schedule retry with jitter
    Note over A,S: A's TTL expires -- A calls guard()
    A->>S: guard() -- renew lease
    S-->>A: success -- extended ttlAt
    Note over A,S: After several renewals A stops
    A->>S: release()
    S-->>B: pub/sub notification: released
    B->>S: acquire mutex
    S-->>B: success -- owner = B
    B->>B: onAcquired()
```

## Locker 获取时序图

```mermaid
sequenceDiagram
autonumber
    participant T as Thread
    participant L as SimbaLocker
    participant CS as ContendService
    participant S as Backend

    T->>L: acquire(timeout)
    L->>CS: start()
    CS->>S: startContend()
    L->>T: LockSupport.park()
    S-->>CS: onAcquired callback
    CS->>L: onAcquired()
    L->>T: LockSupport.unpark()
    T->>T: critical section executes
    T->>L: close() / release
    L->>CS: stop()
    CS->>S: release mutex
```

## 调度器生命周期时序图

```mermaid
sequenceDiagram
autonumber
    participant App as Application
    participant Sch as AbstractScheduler
    participant CS as ContendService
    participant S as Backend
    participant W as ScheduledExecutor

    App->>Sch: start()
    Sch->>CS: start()
    CS->>S: startContend()
    S-->>CS: onAcquired -- becomes owner
    CS->>Sch: WorkContender.onAcquired()
    Sch->>W: scheduleAtFixedRate(work)
    loop Every period
        W->>Sch: work()
        Sch->>App: work() executes
    end
    Note over S,CS: Ownership expires
    S-->>CS: onReleased
    CS->>Sch: WorkContender.onReleased()
    Sch->>W: cancel future
```

## 后续步骤

- [配置参考](/zh/guide/configuration) -- 调整每个后端的 TTL、过渡期和初始延迟。
- [架构概览](/architecture/) -- 深入了解抽象链的工作原理。
- [参与贡献](/zh/guide/contributing) -- 设置开发环境并运行测试套件。
