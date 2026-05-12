---
layout: home

hero:
  name: "Simba"
  text: "JVM 分布式互斥锁"
  tagline: "为 Kotlin 和 Java 应用提供易于使用的分布式锁服务 -- 支持 JDBC、Redis 和 Zookeeper 后端。"
  actions:
    - theme: brand
      text: 快速开始
      link: /zh/guide/
    - theme: alt
      text: 架构设计
      link: /zh/architecture/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Simba

features:
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
    title: 多种后端支持
    details: "选择最适合你技术栈的存储方案：基于乐观锁的 JDBC/MySQL、基于原子 Lua 脚本和发布/订阅的 Redis，或基于 Apache Curator 的 Zookeeper。"
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
    title: 领导者选举
    details: "自动化的所有权管理，支持可配置的 TTL 和过渡期。当前领导者在到期前续租；竞争者使用随机抖动等待，避免惊群效应。"
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/></svg>
    title: RAII 风格加锁
    details: "SimbaLocker 实现了 AutoCloseable 接口，你可以在 try-with-resources 代码块中获取和释放锁，并支持可选的超时机制。"
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
    title: 定时任务调度
    details: "AbstractScheduler 仅在领导者实例上执行周期性工作。内置固定速率和固定延迟两种调度策略。"
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
    title: Spring Boot 自动配置
    details: "只需引入 starter 依赖并设置一个属性。Simba 会根据你的应用配置自动装配正确的后端实现。"
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4-4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>
    title: 惊群效应防护
    details: "竞争时间中 -200ms 到 +1000ms 的随机抖动机制，将竞争者分散开来，避免同时尝试获取锁。"
---

## 工作原理

Simba 采用协作式领导者选举协议。每个竞争者争夺一个命名的互斥锁，获胜者在可配置的 TTL 窗口内成为所有者。当 TTL 到期时，进入过渡期，当前所有者可以优先续租。非所有者竞争者使用随机抖动唤醒，以减少冲突。

```mermaid
stateDiagram-v2
    [*] --> Free
    Free --> Acquired : contender acquires
    Acquired --> Renewing : TTL about to expire
    Renewing --> Acquired : guard succeeds
    Renewing --> Transition : TTL expired
    Transition --> Acquired : owner renews grace
    Transition --> Free : transition expires
    Free --> Acquired : other contender acquires
```

## 三种锁 API

Simba 提供三个层次的抽象，你可以根据使用场景选择最合适的：

```mermaid
graph TD
    subgraph sg_21 ["API Levels"]
        direction TB
        H["AbstractScheduler<br>Leader-gated periodic jobs"]
        L["SimbaLocker<br>RAII / try-with-resources"]
        M["MutexContender<br>Callback-based"]
    end

    M -->|"wraps"| L
    L -->|"wraps"| H

    style H fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style L fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style M fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## 后端存储

```mermaid
graph TD
    subgraph sg_22 ["simba-core"]
        direction TB
        CS["MutexContendService<br>+ MutexContendServiceFactory"]
    end

    subgraph sg_23 ["Backends"]
        direction TB
        J["simba-jdbc<br>JDBC / MySQL"]
        R["simba-spring-redis<br>Redis Lua + Pub/Sub"]
        Z["simba-zookeeper<br>Apache Curator"]
    end

    CS --> J
    CS --> R
    CS --> Z

    style CS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style J fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style R fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Z fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## 快速示例

```kotlin
class MyContender : AbstractMutexContender("my-mutex") {
    override fun onAcquired(mutexState: MutexState) {
        println("I am the owner!")
    }
    override fun onReleased(mutexState: MutexState) {
        println("Lost leadership.")
    }
}
```
