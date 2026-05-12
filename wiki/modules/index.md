---
title: Modules Overview
description: Module structure and dependency graph for the Simba distributed mutex library.
---

# Modules Overview

Simba is organized as a multi-module Gradle project. Each module has a focused responsibility, from core abstractions to backend-specific implementations and Spring Boot auto-configuration.

## Module Dependency Graph

```mermaid
graph TB
    subgraph sg_24 ["Build & Analysis"]

        BOM["simba-bom"]
        DEPS["simba-dependencies"]
        CR["code-coverage-report"]
    end
    subgraph sg_25 ["Application"]

        EX["simba-example"]
    end
    subgraph sg_26 ["Spring Boot"]

        STARTER["simba-spring-boot-starter"]
    end
    subgraph sg_27 ["Backend Implementations"]

        JDBC["simba-jdbc"]
        REDIS["simba-spring-redis"]
        ZK["simba-zookeeper"]
    end
    subgraph sg_28 ["Core"]

        CORE["simba-core"]
        TEST["simba-test"]
    end

    CORE --> DEPS
    JDBC --> CORE
    REDIS --> CORE
    ZK --> CORE
    STARTER --> CORE
    STARTER -.->|"springRedisSupport"| REDIS
    STARTER -.->|"jdbcSupport"| JDBC
    STARTER -.->|"zookeeperSupport"| ZK
    TEST --> CORE
    EX --> STARTER
    CR --> JDBC
    CR --> REDIS
    CR --> ZK
    CR --> STARTER
    CR --> CORE
    CR --> TEST

    style BOM fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style DEPS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CR fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style EX fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style STARTER fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style JDBC fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style REDIS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ZK fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CORE fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style TEST fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## Module Catalogue

| Module | Role | Key Types | Dependencies |
|---|---|---|---|
| **simba-core** | Core interfaces, abstract classes, value objects | `MutexContender`, `MutexContendService`, `SimbaLocker`, `AbstractScheduler` | kotlin-logging, cosid-core, guava |
| **simba-jdbc** | JDBC/MySQL backend with optimistic locking | `JdbcMutexContendService`, `JdbcMutexOwnerRepository` | simba-core, JDBC driver |
| **simba-spring-redis** | Redis backend with Lua scripts and pub/sub | `SpringRedisMutexContendService`, Lua scripts | simba-core, spring-data-redis |
| **simba-zookeeper** | Zookeeper backend using Curator LeaderLatch | `ZookeeperMutexContendService` | simba-core, curator-recipes |
| **simba-spring-boot-starter** | Auto-configuration for all backends | `SimbaJdbcAutoConfiguration`, `SimbaSpringRedisAutoConfiguration`, `SimbaZookeeperAutoConfiguration` | simba-core + conditional backend deps |
| **simba-test** | TCK (Technology Compatibility Kit) | `MutexContendServiceSpec`, `LockSpec` | simba-core, JUnit 5 |
| **simba-bom** | BOM (Bill of Materials) for version management | -- | -- |
| **simba-dependencies** | Dependency version constraints | -- | -- |
| **simba-example** | Example application | `ExampleApp` | simba-spring-boot-starter |
| **code-coverage-report** | JaCoCo aggregated report | -- | all modules |

## Backend Comparison

```mermaid
graph LR
    subgraph sg_29 ["JDBC Backend"]

        J_REPO["MutexOwnerRepository"]
        J_SCHED["ScheduledThreadPoolExecutor<br>(polling)"]
        J_DDL["simba_mutex table<br>optimistic locking"]
    end
    subgraph sg_30 ["Redis Backend"]

        R_LUA["Lua Scripts<br>acquire/guard/release"]
        R_PS["Pub/Sub<br>RedisMessageListenerContainer"]
        R_ZSET["Sorted Set<br>contender queue"]
    end
    subgraph sg_31 ["Zookeeper Backend"]

        Z_LATCH["LeaderLatch<br>/simba/{mutex}"]
        Z_CUR["CuratorFramework"]
    end

    style J_REPO fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style J_SCHED fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style J_DDL fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style R_LUA fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style R_PS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style R_ZSET fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Z_LATCH fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style Z_CUR fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

| Feature | JDBC | Redis | Zookeeper |
|---|---|---|---|
| **Coordination mechanism** | Poll with `ScheduledThreadPoolExecutor` | Lua scripts + pub/sub | Curator `LeaderLatch` |
| **Lock storage** | `simba_mutex` table | Redis key (`simba:{mutex}`) | ZNode (`/simba/{mutex}`) |
| **Ownership transfer** | Optimistic locking via `version` column | Sorted set wait queue + pub/sub notification | ZK watcher on latch participants |
| **Time source** | MySQL `current_timestamp(3)` | System clock (client-side) | ZK server time |
| **External dependency** | MySQL instance | Redis instance | ZooKeeper ensemble |
| **Best for** | Existing relational DB infrastructure | High-throughput, low-latency | Strong consistency guarantees |

## Gradle Feature Variants

The `simba-spring-boot-starter` uses Gradle feature variants so consumers only pull the backend dependency they need:

```kotlin
dependencies {
    // Pull only the Redis backend
    implementation("me.ahoo.simba:simba-spring-boot-starter") {
        capabilities {
            requireCapability("me.ahoo.simba:spring-redis-support")
        }
    }
}
```

Available feature variants:

| Variant | Capability | Backend Module |
|---|---|---|
| `springRedisSupport` | `me.ahoo.simba:spring-redis-support` | simba-spring-redis |
| `jdbcSupport` | `me.ahoo.simba:jdbc-support` | simba-jdbc |
| `zookeeperSupport` | `me.ahoo.simba:zookeeper-support` | simba-zookeeper |

## See Also

- [simba-core](./simba-core) -- core abstractions and design patterns
- [simba-jdbc](./simba-jdbc) -- JDBC/MySQL backend
- [simba-spring-redis](./simba-spring-redis) -- Redis backend
- [simba-zookeeper](./simba-zookeeper) -- Zookeeper backend
- [simba-spring-boot-starter](./simba-spring-boot-starter) -- Spring Boot auto-configuration
- [simba-test](./simba-test) -- TCK test base classes
