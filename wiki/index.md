---
layout: home

hero:
  name: "Simba"
  text: "Distributed Mutex for the JVM"
  tagline: "Easy-to-use distributed lock services for Kotlin and Java applications -- backed by JDBC, Redis, or Zookeeper."
  actions:
    - theme: brand
      text: Get Started
      link: /guide/
    - theme: alt
      text: Architecture
      link: /architecture/
    - theme: alt
      text: GitHub
      link: https://github.com/Ahoo-Wang/Simba

features:
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 2L2 7l10 5 10-5-10-5z"/><path d="M2 17l10 5 10-5"/><path d="M2 12l10 5 10-5"/></svg>
    title: Multiple Backends
    details: "Choose the storage that fits your stack: JDBC/MySQL with optimistic locking, Redis with atomic Lua scripts and pub/sub, or Zookeeper via Apache Curator."
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
    title: Leader Election
    details: "Automatic ownership with configurable TTL and transition periods. The current leader renews before expiry; contenders wait with jitter to avoid thundering herd."
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0110 0v4"/></svg>
    title: RAII-Style Locking
    details: "SimbaLocker implements AutoCloseable so you can acquire and release locks in a try-with-resources block with optional timeout support."
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/></svg>
    title: Scheduled Tasks
    details: "AbstractScheduler runs periodic work only on the leader instance. Fixed-rate and fixed-delay strategies are built in."
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M22 12h-4l-3 9L9 3l-3 9H2"/></svg>
    title: Spring Boot Auto-Configuration
    details: "Drop in the starter dependency and set one property. Simba auto-configures the correct backend based on your application properties."
  - icon: <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4-4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 00-3-3.87"/><path d="M16 3.13a4 4 0 010 7.75"/></svg>
    title: Thundering Herd Prevention
    details: "Random jitter of -200ms to +1000ms in contention timing spreads out contenders so they do not all attempt acquisition at the same instant."
---

## How It Works

Simba uses a cooperative leader-election protocol. Each contender competes for a named mutex, and the winner becomes the owner for a configurable TTL window. When the TTL expires, a transition period begins during which the current owner can renew its lease preferentially. Non-owner contenders wake up with random jitter to reduce collision.

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

## Three Lock APIs

Simba offers three levels of abstraction so you can pick the one that best matches your use case:

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

## Backend Storage

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

## Quick Example

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
