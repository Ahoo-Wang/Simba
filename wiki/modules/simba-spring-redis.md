---
title: simba-spring-redis Module
description: Redis backend for Simba distributed mutex -- Lua scripts for atomic acquire/guard/release and pub/sub broadcasts for ownership changes.
---

# simba-spring-redis Module

The `simba-spring-redis` module provides a Redis-based distributed mutex backend using Spring Data Redis. Lock operations are atomic Lua scripts executed server-side, and Redis pub/sub broadcasts ownership changes so waiting contenders react immediately instead of waiting for their next poll.

## Architecture Overview

```mermaid
graph TB
    subgraph sg_app ["Application"]
        SVC["SpringRedisMutexContendService<br>(LeaseContendService)"]
        STORE["SpringRedisMutexLeaseStore"]
        KEYS["RedisMutexKeys"]
    end
    subgraph sg_lua ["Lua Scripts"]
        ACQ["mutex_acquire.lua<br>SET NX PX + publish acquired"]
        GRD["mutex_guard.lua<br>SET XX PX (renew)"]
        REL["mutex_release.lua<br>DEL + publish released"]
    end
    subgraph sg_data ["Redis"]
        KEY["simba:{mutex}<br>String (ownerId)"]
        CHAN["simba:{mutex}<br>Pub/Sub channel"]
    end

    SVC --> STORE
    STORE --> KEYS
    SVC --> KEYS
    STORE --> ACQ
    STORE --> GRD
    STORE --> REL
    ACQ --> KEY
    ACQ --> CHAN
    GRD --> KEY
    REL --> KEY
    REL --> CHAN
    CHAN --> SVC

    style SVC fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style STORE fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style KEYS fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style ACQ fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style GRD fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style REL fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style KEY fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
    style CHAN fill:#2d333b,stroke:#6d5dfc,color:#e6edf3
```

## Redis Keys and Channels

All names share the `{mutex}` hash tag, so they land on one Redis Cluster slot. [`RedisMutexKeys`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-spring-redis/src/main/kotlin/me/ahoo/simba/spring/redis/RedisMutexKeys.kt) is the single Kotlin source of these names.

| Name | Type | Purpose |
|---|---|---|
| `simba:{mutex}` | String | `contenderId` of the current owner, expiring after `ttl + transition` (`PX`). |
| `simba:{mutex}` | Pub/Sub channel | Broadcasts `acquired@@{ownerId}` and `released@@{ownerId}` to every contender. |
| `simba:{mutex}:fence` | String (counter) | Fencing counter, incremented once per ownership term; never expires. |
| `simba:{mutex}:token` | String | Fencing token of the current term; expires with the lease. |

## Lua Scripts

Every script receives its keys through `KEYS` (Redis Cluster compliant). `mutex_acquire.lua` and `mutex_guard.lua` return `{ownerId, remaining lease in ms, fencing token}`, or `{'', 0, 0}` when there is no owner.

### mutex_acquire.lua

[`mutex_acquire.lua`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-spring-redis/src/main/resources/mutex_acquire.lua)

```lua
if redis.call('set', mutexKey, contenderId, 'nx', 'px', lease) then
    local token = redis.call('incr', fenceKey)
    redis.call('set', tokenKey, token, 'px', lease)
    redis.call('publish', mutexKey, 'acquired@@' .. contenderId)
    return { contenderId, tonumber(lease), token };
end
local ownerId = redis.call('get', mutexKey)
if not ownerId then
    return { '', 0, 0 };
end
return { ownerId, redis.call('pttl', mutexKey), tonumber(redis.call('get', tokenKey) or '0') };
```

`SET NX PX` acquires atomically for `ttl + transition` and announces the new owner. On failure the script reports the current owner and its remaining lease.

### mutex_guard.lua

[`mutex_guard.lua`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-spring-redis/src/main/resources/mutex_guard.lua) renews the lease only when the caller still owns it (`SET XX PX`), otherwise it reports the current owner. It never creates a lease that has already expired.

### mutex_release.lua

[`mutex_release.lua`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-spring-redis/src/main/resources/mutex_release.lua)

```lua
if redis.call('get', mutexKey) ~= contenderId then
    return 0;
end
redis.call('del', mutexKey, tokenKey)
redis.call('publish', mutexKey, 'released@@' .. contenderId)
return 1;
```

Only the owner can release. The release is broadcast on the mutex channel, so every live contender contends immediately and one wins; no wake-up can be lost to a crashed contender.

## Key Classes

| Class | Role |
|---|---|
| [`SpringRedisMutexContendService`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-spring-redis/src/main/kotlin/me/ahoo/simba/spring/redis/SpringRedisMutexContendService.kt) | `LeaseContendService` subclass; subscribes in `onStart()`, unsubscribes in `onStop()`, and reacts to owner events. |
| [`SpringRedisMutexLeaseStore`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-spring-redis/src/main/kotlin/me/ahoo/simba/spring/redis/SpringRedisMutexLeaseStore.kt) | `MutexLeaseStore`: runs `mutex_guard.lua` when renewing, `mutex_acquire.lua` otherwise, and `mutex_release.lua`. |
| [`AcquireResult`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-spring-redis/src/main/kotlin/me/ahoo/simba/spring/redis/AcquireResult.kt) | Parses a script reply into the owner and the absolute lease end (`transitionAt`). |
| [`OwnerEvent`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-spring-redis/src/main/kotlin/me/ahoo/simba/spring/redis/OwnerEvent.kt) | Parses pub/sub messages `{event}@@{ownerId}`. |

| Event | Reaction |
|---|---|
| `acquired` | Update the observed owner. |
| `released` | Clear the observed owner and call `contendNow()` for an immediate attempt. |

## Sequence Diagram -- Acquisition and Hand-off

```mermaid
sequenceDiagram
autonumber
    participant S1 as Service-1
    participant Redis as Redis
    participant S2 as Service-2

    Note over S1,S2: Both services subscribe to simba:{m}
    S1->>Redis: mutex_acquire.lua (NX, PX=ttl+transition)
    Redis-->>S1: {S1, lease}
    Redis->>S2: publish acquired@@S1
    S2->>Redis: mutex_acquire.lua
    Redis-->>S2: {S1, remaining}
    loop While owner
        S1->>Redis: mutex_guard.lua
        Redis-->>S1: {S1, lease}
    end
    S1->>Redis: mutex_release.lua
    Redis->>S2: publish released@@S1
    S2->>Redis: mutex_acquire.lua
    Redis-->>S2: {S2, lease}
```

## Properties

| Property | Default | Description |
|---|---|---|
| `simba.redis.enabled` | `true` | Enable the Redis backend |
| `simba.redis.ttl` | `10s` | Time after acquisition at which the owner renews |
| `simba.redis.transition` | `6s` | Grace period; the key expires after `ttl + transition` |

## Factory

[`SpringRedisMutexContendServiceFactory`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-spring-redis/src/main/kotlin/me/ahoo/simba/spring/redis/SpringRedisMutexContendServiceFactory.kt)

```kotlin
class SpringRedisMutexContendServiceFactory(
    ttl: Duration,
    transition: Duration,
    redisTemplate: StringRedisTemplate,
    listenerContainer: RedisMessageListenerContainer,
    handleExecutor: Executor = ForkJoinPool.commonPool(),
    scheduledExecutorService: ScheduledExecutorService = ContendExecutors.newScheduler("simba-redis"),
    ioExecutor: ExecutorService = ContendExecutors.newIoExecutor("simba-redis-io")
) : MutexContendServiceFactory, AutoCloseable
```

All services share the scheduler (contention triggers and lease watchdogs) and the I/O executor (script calls). The factory owns both and shuts them down on `close()`. The Spring Boot starter passes its `simbaHandleExecutor` bean as `handleExecutor`.

### Fencing Tokens

A successful `SET NX` starts a new ownership term: `mutex_acquire.lua` increments `simba:{mutex}:fence` and stores the result in `simba:{mutex}:token` with the lease's `PX`. `mutex_guard.lua` renews both keys and keeps the token, and `mutex_release.lua` deletes the token key while keeping the counter. The token therefore increases strictly per term and stays stable within it (see [ADR 0002](https://github.com/Ahoo-Wang/Simba/blob/main/docs/adr/0002-fencing-token.md)).

A node ignores the `acquired@@{ownerId}` broadcast for its own acquisition: the script reply carries the token, while the broadcast cannot, because older nodes parse the message as exactly two fields.

::: warning Durability
Monotonicity requires Redis to persist the counter (AOF with `appendfsync always`, or equivalent). If Redis restarts without it, the counter resets and tokens can go backwards.
:::

## Upgrading to 4.0

All nodes must run Simba 3.2 or later before upgrading to 4.0: 4.0 no longer subscribes to the per-contender channels or cleans up the contender queue that Simba 3.1 relied on. Mixed 3.2/3.3/4.0 nodes are compatible.

## Dependencies

```
simba-spring-redis
  ├── simba-core
  └── spring-data-redis
```

## See Also

- [simba-core Module](./simba-core) -- core interfaces and `LeaseContendService`
- [simba-spring-boot-starter](./simba-spring-boot-starter) -- auto-configuration with `simba.redis.*` properties
- [simba-jdbc](./simba-jdbc) -- JDBC alternative backend
