---
title: API Reference
description: Public API overview for the Simba distributed mutex library -- interfaces, classes, and the abstraction layers that compose the contender-based locking model.
---

# API Reference

Simba exposes a small, layered API built around the concept of **mutex contention**: multiple service instances compete for exclusive ownership of a named mutex, and the winner receives callbacks when it acquires or loses the lock. All public types live in the `me.ahoo.simba.*` packages under the `simba-core` module.

## API Layer Architecture

The diagram below shows how the public API types are organized into layers. User-facing abstractions (Locker, Scheduler) sit on top, the core contention protocol lives in the middle, and backend-specific factories sit beneath.

```mermaid
graph TB
    subgraph User["User-facing API"]
        style User fill:#161b22,stroke:#30363d,color:#e6edf3
        LS["Locker / SimbaLocker"]
        AS["AbstractScheduler"]
        CS["ScheduleConfig"]
    end
    subgraph Core["Core Contention Protocol"]
        style Core fill:#161b22,stroke:#30363d,color:#e6edf3
        MR["MutexRetriever"]
        MC["MutexContender"]
        MRS["MutexRetrievalService"]
        MCS["MutexContendService"]
        MRF["MutexRetrievalServiceFactory"]
        MCF["MutexContendServiceFactory"]
        AMCS["AbstractMutexContendService"]
        AMC["AbstractMutexContender"]
        AMRS["AbstractMutexRetrievalService"]
    end
    subgraph Model["Value Objects"]
        style Model fill:#161b22,stroke:#30363d,color:#e6edf3
        MO["MutexOwner"]
        MS["MutexState"]
        CP["ContendPeriod"]
        CIG["ContenderIdGenerator"]
    end

    LS -->|creates| MC
    LS -->|uses| MCF
    AS -->|uses| MCF
    AS -->|creates| AMC
    MC -->|extends| MR
    MCS -->|extends| MRS
    AMCS -->|extends| AMRS
    AMC -->|implements| MC
    AMRS -->|implements| MRS
    AMCS -->|implements| MCS
    MCF -->|creates| MCS
    MRF -->|creates| MRS
    AMCS -->|uses| MO
    AMRS -->|produces| MS
    CP -->|uses| MO
    MS -->|contains| MO

    style LS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style AS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style MR fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style MC fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style MRS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style MCS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style MRF fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style MCF fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style AMCS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style AMC fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style AMRS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style MO fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style MS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CP fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CIG fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## Public Type Catalogue

### Core Interfaces

| Type | Kind | Package | Description |
|---|---|---|---|
| [`MutexRetriever`](./core-interfaces#mutexretriever) | Interface | `me.ahoo.simba.core` | Minimal contract: provides a `mutex` name and receives `notifyOwner` callbacks |
| [`MutexContender`](./core-interfaces#mutexcontender) | Interface | `me.ahoo.simba.core` | Extends `MutexRetriever` with `contenderId` and `onAcquired`/`onReleased` lifecycle |
| [`MutexRetrievalService`](./core-interfaces#mutexretrievalservice) | Interface | `me.ahoo.simba.core` | Lifecycle-managed retrieval service with `start()`/`stop()` and status tracking |
| [`MutexContendService`](./core-interfaces#mutexcontendservice) | Interface | `me.ahoo.simba.core` | Extends retrieval with contender-bound ownership queries (`isOwner`, `isInTtl`) |
| [`MutexRetrievalServiceFactory`](./core-interfaces#mutexretrievalservicefactory) | Interface | `me.ahoo.simba.core` | Factory for creating `MutexRetrievalService` instances |
| [`MutexContendServiceFactory`](./core-interfaces#mutexcontendservicefactory) | Interface | `me.ahoo.simba.core` | Factory for creating `MutexContendService` instances |

### Abstract Base Classes

| Type | Kind | Package | Description |
|---|---|---|---|
| [`AbstractMutexContender`](./core-interfaces#abstractmutexcontender) | Abstract Class | `me.ahoo.simba.core` | Base contender with default logging for `onAcquired`/`onReleased` |
| [`AbstractMutexRetrievalService`](./core-interfaces#abstractmutexretrievalservice) | Abstract Class | `me.ahoo.simba.core` | Template method for retrieval lifecycle and async owner notification |
| [`AbstractMutexContendService`](./core-interfaces#abstractmutexcontendservice) | Abstract Class | `me.ahoo.simba.core` | Delegates to abstract `startContend()`/`stopContend()` implemented by backends |

### Value Objects

| Type | Kind | Package | Description |
|---|---|---|---|
| [`MutexOwner`](./core-interfaces#mutexowner) | Immutable Class | `me.ahoo.simba.core` | Snapshot of lock ownership: `ownerId`, `acquiredAt`, `ttlAt`, `transitionAt` |
| [`MutexState`](./core-interfaces#mutexstate) | Data Class | `me.ahoo.simba.core` | Transition pair: `before` and `after` owners, with change detection |
| [`ContendPeriod`](./core-interfaces#contendperiod) | Class | `me.ahoo.simba.core` | Computes scheduling delays for owner renewal vs. contender retry |
| [`ContenderIdGenerator`](./core-interfaces#contenderidgenerator) | Interface | `me.ahoo.simba.core` | Generates unique contender IDs; provides `HOST` and `UUID` strategies |

### Locker API

| Type | Kind | Package | Description |
|---|---|---|---|
| [`Locker`](./locker-api#locker) | Interface | `me.ahoo.simba.locker` | RAII-style lock interface: `acquire()` with optional timeout, `close()` releases |
| [`SimbaLocker`](./locker-api#simbalocker) | Class | `me.ahoo.simba.locker` | Concrete implementation using `LockSupport.park/unpark` for blocking acquire |

### Scheduler API

| Type | Kind | Package | Description |
|---|---|---|---|
| [`AbstractScheduler`](./scheduler-api#abstractscheduler) | Abstract Class | `me.ahoo.simba.schedule` | Leader-gated scheduled executor: only the mutex owner runs the task |
| [`ScheduleConfig`](./scheduler-api#scheduleconfig) | Data Class | `me.ahoo.simba.schedule` | Scheduling parameters: `FIXED_RATE`/`FIXED_DELAY` strategy, `initialDelay`, `period` |

### Exceptions and Utilities

| Type | Kind | Package | Description |
|---|---|---|---|
| `SimbaException` | Open Class | `me.ahoo.simba` | Root exception type for Simba errors |
| `Simba` | Object | `me.ahoo.simba` | Brand constants: `SIMBA = "simba"`, `SIMBA_PREFIX = "simba."` |
| `Threads` | Object | `me.ahoo.simba.util` | `defaultFactory(domain)` builds a named `ThreadFactory` via Guava |

## Contention Protocol Overview

```mermaid
sequenceDiagram
autonumber
    participant App as Application
    participant Contender as MutexContender
    participant Service as MutexContendService
    participant Backend as Backend (JDBC/Redis/ZK)

    App->>Service: start()
    Service->>Service: status: INITIAL -> STARTING
    Service->>Backend: startContend()

    loop Contention Loop
        Backend->>Backend: attempt acquire / renew
        Backend->>Service: notifyOwner(MutexOwner)
        Service->>Service: status -> RUNNING
        Service->>Contender: onAcquired(MutexState)
        Note over Contender: Contender is now the leader
    end

    App->>Service: stop()
    Service->>Backend: stopContend()
    Backend->>Service: release ownership
    Service->>Contender: onReleased(MutexState)
    Service->>Service: status: RUNNING -> STOPPING -> INITIAL
```

## Ownership Lifecycle

```mermaid
stateDiagram-v2
    [*] --> INITIAL: Service created
    INITIAL --> STARTING: start()
    STARTING --> RUNNING: First contend cycle
    RUNNING --> RUNNING: Renew before TTL
    RUNNING --> STOPPING: stop()
    STOPPING --> INITIAL: Cleanup complete
    RUNNING --> INITIAL: Error during contend

    state RUNNING {
        [*] --> NotOwner
        NotOwner --> Owner: onAcquired()
        Owner --> NotOwner: onReleased() / TTL expiry
        Owner --> Owner: guard/renew
    }
```

## Quick Start

The simplest way to use Simba is through the `MutexContendServiceFactory`:

```kotlin
// 1. Obtain a factory (provided by simba-jdbc, simba-spring-redis, or simba-zookeeper)
val factory: MutexContendServiceFactory = ...

// 2. Create a contender with a mutex name and callbacks
val contender = object : AbstractMutexContender("my-resource") {
    override fun onAcquired(mutexState: MutexState) {
        println("I am the leader: ${contenderId}")
    }
    override fun onReleased(mutexState: MutexState) {
        println("Leadership lost: ${contenderId}")
    }
}

// 3. Create and start the contend service
val service = factory.createMutexContendService(contender)
service.start()

// ... later
service.stop()
```

For RAII-style locking, see the [Locker API](./locker-api). For leader-gated scheduled tasks, see the [Scheduler API](./scheduler-api).

## Module Distribution

The interfaces and abstract classes above live entirely in `simba-core`. Concrete factory implementations are in each backend module:

- **simba-jdbc** -- `JdbcMutexContendServiceFactory`
- **simba-spring-redis** -- `SpringRedisMutexContendServiceFactory`
- **simba-zookeeper** -- `ZookeeperMutexContendServiceFactory`

The `simba-spring-boot-starter` auto-configures the appropriate factory bean based on application properties. See [Module Reference](/modules/) for backend details.

```mermaid
pie title API Type Distribution
    "Interfaces" : 6
    "Abstract Classes" : 4
    "Value Objects / Data Classes" : 4
    "Concrete Classes" : 2
    "Exceptions & Utilities" : 3
```

## See Also

- [Core Interfaces](./core-interfaces) -- detailed documentation of every interface and its methods
- [Locker API](./locker-api) -- RAII-style distributed locking
- [Scheduler API](./scheduler-api) -- leader-gated periodic task execution
