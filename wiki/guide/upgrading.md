---
title: Upgrading
description: Steps and breaking changes when upgrading to Simba 4.x.
---

# Upgrading

Simba follows [Semantic Versioning](https://semver.org/). 4.1, 4.2 and 4.3 are additive (`@SimbaScheduled` and
`SimbaScheduler`, `ContendObserver` and Micrometer metrics, the `simba` Actuator endpoint); only 4.0 needs action.

## To 4.0

Do these before rolling out 4.0:

1. **Redis:** every node contending for the same mutex must already run Simba 3.2 or later. Upgrade from 3.1 through
   3.2/3.3 first.
2. **JDBC:** fencing tokens are on by default. Run
   [`upgrade-simba-mysql-fencing-token.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/init-script/upgrade-simba-mysql-fencing-token.sql)
   on existing tables, or set `simba.jdbc.fencing=false`.
3. **Spring Boot:** with more than one backend module on the classpath, set `simba.backend`. Startup fails otherwise.

API changes:

| Before | 4.0 |
|---|---|
| Subclassing `MutexOwner`, `MutexOwnerEntity` | `MutexOwner` is a final value; `MutexOwnerEntity` is removed |
| `MutexOwner.isInTransition` | Folded into `hasOwner()` |
| `MutexRetrievalServiceFactory` | Removed (it had no implementation) |
| `MutexOwnerRepository` methods other than `acquireAndGetOwner` / `release` | Removed from the interface; initialization and inspection stay on `JdbcMutexOwnerRepository` |
| `AcquireResult.of(String)` | Removed |
| Guava and cosid via `simba-core` | No longer transitive; add them yourself if you use them |

Callbacks now run outside internal locks, and `stop()` waits for its own `onReleased`. Code that called `stop()` while
holding a lock its callbacks also take must release that lock first.

The rationale is in [ADR 0003](https://github.com/Ahoo-Wang/Simba/blob/main/docs/adr/0003-simba-4.md).
