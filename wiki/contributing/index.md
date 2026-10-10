---
title: Contributing
description: Build Simba, run its tests, add a backend with the TCK, and keep the documentation in sync.
---

# Contributing

The workflow, commit conventions, quality gates and release process are defined in
[CONTRIBUTING.md](https://github.com/Ahoo-Wang/Simba/blob/main/CONTRIBUTING.md). This page covers the technical side.
Read [Architecture](/architecture/) first; the invariants an agent or reviewer checks are listed in
[AGENTS.md](https://github.com/Ahoo-Wang/Simba/blob/main/AGENTS.md).

## Build and Test

You need JDK 17 and Docker.

```bash
./gradlew build                                   # all modules
./gradlew simba-core:check                        # tests + detekt for one module
./gradlew simba-core:test --tests me.ahoo.simba.core.LeaseContendServiceTest
./gradlew simba-example:check -PexampleBackend=jdbc   # jdbc | redis | zookeeper (default: redis)
./gradlew codeCoverageReport                      # aggregated JaCoCo report
./gradlew check -PtestJavaVersion=25              # run tests on another JDK; bytecode stays 17
```

| Module | Test infrastructure |
|---|---|
| `simba-core` | None |
| `simba-zookeeper` | Embedded Curator `TestingServer` |
| `simba-spring-redis` | Testcontainers `redis:7.4-alpine` (`RedisFixture`) |
| `simba-jdbc` | Testcontainers `mysql:8.4` initialized with `init-simba-mysql.sql` (`MySqlFixture`) |
| `simba-spring-boot-starter` | Spring Boot test slices |

## Writing Tests

- Name tests after the behavior. New assertions use FluentAssert (`.assert()`); MockK only when a real code path is
  impractical.
- Never `sleep` to synchronize; wait on latches or futures.
- Races in the contention loop are tested deterministically in `LeaseContendServiceTest`, with a manual scheduler and
  I/O executor and no real threads.
- Backend semantics are tested once, in the TCK.

## Backend TCK

`simba-test` provides `MutexContendServiceSpec`. Every backend's test extends it and supplies a factory:

```kotlin
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class MyBackendMutexContendServiceTest : MutexContendServiceSpec() {
    override lateinit var mutexContendServiceFactory: MutexContendServiceFactory

    @BeforeAll
    fun setup() {
        mutexContendServiceFactory = MyBackendMutexContendServiceFactory(/* ... */)
    }

    // optional: enables the observer() case
    override fun createObservedFactory(observer: ContendObserver) = MyBackendMutexContendServiceFactory(observer = observer)
}
```

| Case | Verifies |
|---|---|
| `start` | A single contender acquires, then releases on `stop()`. |
| `restart` | A stopped service starts again and reacquires with the same contender id. |
| `guard` | An owner keeps ownership across several lease periods (renewal works). |
| `multiContend` | Ten contenders over 30 s: never more than one owner, and all agree on who it is. |
| `schedule` | `AbstractScheduler` runs work on the leader. |
| `simbaScheduler` | `SimbaScheduler` runs work on the leader and exposes the fencing token. |
| `observer` | Observer events are reported (skipped without `createObservedFactory`; set `reportsContention = false` for event-driven backends). |

## Adding a Backend

For a lease-based store, implement `MutexLeaseStore` (one atomic `contend`, one `release`) plus a factory that builds
`LeaseContendService` instances with shared `ContendExecutors`. Do not re-implement scheduling, watchdogs or generation
checks in the backend. Then:

1. Extend `MutexContendServiceSpec` for it.
2. Issue a fencing token that increases strictly per term and is stable across renewals (ADR 0002).
3. Add an auto-configuration in the starter: activated by `simba.enabled`, `simba.<backend>.enabled` and
   `simba.backend`, registered in `META-INF/spring/...AutoConfiguration.imports` and in
   `additional-spring-configuration-metadata.json`, plus a `SimbaBackend` entry.
4. Document it in [Backends](/guide/backends) (both languages).

Open an issue first: a new backend, dependency, or change to the Redis scripts, JDBC SQL or public `simba-core` API
needs agreement before code.

## Documentation

| Where | What |
|---|---|
| `README.md` / `README.zh-CN.md` | Landing page: what Simba is, install, one example per API |
| `wiki/` (this site) | Guides, reference and architecture; English at the root, Chinese under `zh/` |
| `docs/adr/` | Design decisions and their rationale |
| `AGENTS.md` | Invariants and traps for maintainers and coding agents |

Every page change is made in both languages. After editing English pages, regenerate `wiki/llms-full.txt`:

```bash
cd wiki
pnpm install
pnpm run sync:llms
pnpm run build
```
