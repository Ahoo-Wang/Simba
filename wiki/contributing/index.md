---
title: Contributing
description: Build Simba, run its tests, change the engine safely, add a backend with the TCK, and keep the documentation in sync.
---

# Contributing

The workflow, commit conventions, quality gates and release process are defined in
[CONTRIBUTING.md](https://github.com/Ahoo-Wang/Simba/blob/main/CONTRIBUTING.md). This page covers the technical side.
Read [Architecture](/architecture/) first; the invariants an agent or reviewer checks are listed in
[AGENTS.md](https://github.com/Ahoo-Wang/Simba/blob/main/AGENTS.md).

## Build and Test

You need JDK 21 and Docker.

```bash
./gradlew build                                   # all modules
./gradlew simba-core:check                        # tests + detekt for one module
./gradlew simba-core:test --tests me.ahoo.simba.engine.EngineTest
./gradlew simba-testing:test -Psimba.simulation.seed=42   # replay one simulation seed
./gradlew simba-example:check -PexampleBackend=jdbc   # jdbc | redis | zookeeper (default: redis)
./gradlew codeCoverageReport                      # aggregated JaCoCo report
./gradlew test -PtestJavaVersion=25               # run tests on another JDK; bytecode stays 21
```

| Module | Test infrastructure |
|---|---|
| `simba-core`, `simba-testing`, `simba-tck` | None |
| `simba-zookeeper` | Embedded Curator `TestingServer` |
| `simba-redis` (also covers `simba-spring-redis`) | Testcontainers `redis:7.4-alpine` (`RedisFixture`) |
| `simba-jdbc` | Testcontainers `mysql:8.4` and `postgres:17` (`LeaseDatabaseFixture`) |
| `simba-spring-boot-starter` | Spring Boot test slices |

## Writing Tests

- Name tests after the behavior. Assertions use FluentAssert (`.assert()`); MockK only when a real code path is
  impractical.
- Never `sleep` to synchronize; wait on queues, latches or futures.
- **Engine changes** are tested as tables of events in and effects out against `step()`, with no executor. Keep
  `SimulationSpec` (the invariants over 10⁴ seeds) green.
- **Threaded client tests** run on virtual time: `VirtualScheduler`, direct I/O and callback executors, and
  `awaitIdle()` between timers (see `VirtualSimba` in the `simba-testing` tests).
- Backend semantics are tested once, in the TCK.

## Adding a Backend

1. Implement `LeaseBackend`: a bounded number of calls per method, no scheduling, no local lease state, remaining
   times measured by the backend's clock and returned as durations, tokens that strictly increase and never reset,
   expired sessions that are never revived, keep-alive replies that list every owned lease.
2. Extend [`LeaseBackendSpec`](/guide/testing#backend-tck) for it. A backend with an injectable clock overrides
   `clock`; otherwise time-based tests poll until the backend's own lease ran out.
3. Add a starter auto-configuration activated by `simba.enabled`, `simba.<backend>.enabled` and `simba.backend`,
   registered in `META-INF/spring/...AutoConfiguration.imports` and `additional-spring-configuration-metadata.json`,
   plus a `SimbaBackend` entry and an example backend.
4. Document it in [Backends](/guide/backends) (both languages).

Open an issue first: a new backend, dependency, change of the `LeaseBackend` SPI, the Redis scripts or key names,
the JDBC SQL or schema, or the public `simba-core` API needs agreement before code.

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
