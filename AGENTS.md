# Simba - Agent Instructions

Simba is a JVM (Kotlin, Java 17 bytecode) distributed mutex / leader-election library over JDBC (MySQL), Redis, or
Zookeeper. Its whole contract: per mutex, at most one contender *observes itself* as owner of a time-bounded lease,
and every contender receives ordered acquire/release callbacks.

That view is weakly consistent. A paused process can still act after its lease ended; only a protected resource that
checks the fencing token (ADR 0002) can reject a stale owner. Never document or implement anything that promises more
(no "no split-brain", no work continuing while the backend is unreachable).

This file records what is not obvious from the code. Describe the code as it is; design direction belongs in
`docs/adr/`. When an invariant below changes, change this file in the same PR.

## Commands

```bash
./gradlew build                                          # Build all modules
./gradlew <module>:check                                 # Tests + detekt (autoCorrect rewrites sources)
./gradlew <module>:test --tests <fully.qualified.TestClass>
./gradlew simba-example:check -PexampleBackend=jdbc|redis|zookeeper   # default: redis
./gradlew test -PtestJavaVersion=25                      # Tests on another JDK; bytecode stays 17
./gradlew codeCoverageReport                             # Aggregated JaCoCo report
```

| Module check | Needs |
|---|---|
| `simba-core`, `simba-zookeeper`, `simba-spring-boot-starter` | Nothing (Zookeeper tests use embedded Curator `TestingServer`) |
| `simba-spring-redis` | Docker: Testcontainers `redis:7.4-alpine` (`RedisFixture`) |
| `simba-jdbc` | Docker: Testcontainers `mysql:8.4` (`MySqlFixture`, schema from `simba-jdbc/src/init-script/init-simba-mysql.sql`) |

CI (`.github/workflows/integration-test.yml`): `simba-core:check`, then the Redis, Zookeeper and JDBC module checks,
the `simba-example` backend matrix, and a JDK 25 test run. Wiki commands are in `wiki/AGENTS.md`.

## Architecture

### Modules

```
simba-core  ←  simba-jdbc | simba-spring-redis | simba-zookeeper  ←  simba-spring-boot-starter
              (simba-test: backend TCK)                              (simba-example: per-backend demo app)
```

- `simba-core` knows no backend and depends only on kotlin-logging. Backend behavior stays in its module; shared API
  types live in core.
- `simba-test` holds the TCK every backend must pass: `MutexContendServiceSpec`, `LockSpec`.
- `simba-bom`, `simba-dependencies`: publication metadata. `code-coverage-report`: JaCoCo aggregation only.

### Layers (top to bottom)

1. **User APIs** (`simba-core`): `MutexContender` callbacks (`AbstractMutexContender`); `SimbaLocker` (blocking
   lock); `SimbaScheduler` / `AbstractScheduler` (leader-only periodic work, ADR 0004). Starter adds
   `@SimbaScheduled`, Micrometer metrics, and the Actuator endpoint `simba` (ADR 0005, 0006).
2. **`MutexContendService`** (one per contender and mutex, created by a `MutexContendServiceFactory`):
   lifecycle, generations, ordered notification. Base: `AbstractMutexRetrievalService` → `AbstractMutexContendService`.
3. **Contention engine**: `LeaseContendService` (ADR 0001) is the shared polling loop for JDBC and Redis: scheduling,
   watchdog, retries, late-acquisition release. Zookeeper does not use it; it wraps Curator `LeaderLatch`.
4. **`MutexLeaseStore`**: the only thing a polling backend implements — one atomic acquire/renew/release call per
   invocation, no scheduling, no notification (`JdbcMutexLeaseStore`, `SpringRedisMutexLeaseStore`).

`LeaseConfig` is the single place that validates `ttl` / `transition`. `ContendPeriod` computes the next attempt.

## Invariants

### Lease model
- An owner holds `ttlAt` (renew deadline) and `transitionAt = ttlAt + transition` (grace period). Inside the grace
  period only the current owner may renew; others may acquire only after `transitionAt`.
- `ContendPeriod`: owners retry at `ttlAt`; contenders retry at `transitionAt` plus jitter in `[-200, 1000)` ms
  (`[0, 1000)` when transition is zero).
- JDBC: one `simba_mutex` row per mutex; the acquire `UPDATE` encodes the rule above using database time.
- Redis: key `simba:{mutex}` with `PX = ttl + transition`; acquire is `SET NX`, renew (guard) is `SET XX` by the owner
  only; release deletes the key and publishes `released` on the mutex channel so every contender contends at once.
- Zookeeper: `LeaderLatch` at `/simba/{mutex}`; ttl/transition do not apply (`ttlAt = transitionAt = MAX`).

### Fencing tokens (ADR 0002)
- `MutexOwner.fencingToken` increases strictly per ownership term and is stable within it; `0` means none.
- Zookeeper: the winning latch node's czxid, never its sequence number (container parents get reaped and restart
  sequences).
- Redis: `INCR simba:{mutex}:fence` on a successful `SET NX`; the term's token is stored in `simba:{mutex}:token`.
  Nodes ignore broadcasts of their own acquisition.
- JDBC (`simba.jdbc.fencing`, on by default): `fencing_token` advances inside the acquire `UPDATE` only on a new term.
  That assignment must stay first in `SET`: MySQL evaluates `SET` left to right with already-updated values.

### Lifecycle
- `Status`: `INITIAL → STARTING → RUNNING → STOPPING → INITIAL`. A failed `start()` returns to `INITIAL`; services
  restart with the same `contenderId`.
- `stop()` always delivers a release (`MutexOwner.NONE`) notification, even if `stopContend()` throws. `stop()` throws
  when not `RUNNING`; `close()` is idempotent (stops only when `RUNNING`).
- Each `start()` begins a new generation. Notifications from an older generation are dropped; while inactive, only
  `NONE` may be applied.
- A late acquisition that completes after `stop()` must be released remotely — unless a restarted lifecycle with the
  same `contenderId` is now active (releasing would kill the new lease). `LeaseContendService.adopt` owns this.
- Local lease guard: each successful acquire/renew arms a watchdog at `transitionAt`, measured from when the call was
  *sent*. It revokes local ownership if no renewal succeeds by then, even while a backend call hangs. A failed renew
  keeps ownership while the lease is still valid and retries with halving backoff (min 100 ms); otherwise it revokes
  and retries after `ttl`. With `transition = 0`, ownership can flap.
- `leaseRemaining` / `ensureLease` (ADR 0007) read that watchdog deadline in `LeaseContendService` (lock-free, zero
  until the acquisition is observed); other services derive it from `afterOwner`, unbounded on Zookeeper. It is a
  hint to start a step, never a substitute for the fencing check.
- The starter sets `JdbcMutexOwnerRepository.queryTimeout` to `simba.jdbc.ttl` (a call slower than the ttl could not keep ownership anyway).

### Threading
- Owner notifications run asynchronously through a `SequentialExecutor` over `handleExecutor` (library default
  `ForkJoinPool.commonPool()`; the starter injects the dedicated `simbaHandleExecutor` bean). The state lock is never
  held while callbacks run.
- `stop()` returns only after its own `onReleased` ran (inline when called from a callback). Callers of `stop()` must
  not hold locks their callbacks take. Keep callbacks short.
- `LeaseContendService` separates a trigger `ScheduledExecutorService` (never blocks) from an `ioExecutor` running
  `MutexLeaseStore` calls, with at most one call in flight per service and lifecycle. JDBC and Redis factories own
  shared executors from `ContendExecutors` (daemon, idle threads reclaimed) and shut them down on `close()`. A directly
  constructed service defaults to its own idle-reclaimed scheduler and runs I/O on the trigger thread.
- `SimbaLocker` is owned by one thread at a time; interruption does not cancel `acquire()` (the flag is restored).
- `SimbaScheduler` and `AbstractScheduler` share `ScheduledWorkRunner`: the worker executor is created on acquire,
  work is cancelled with interrupt on release, and the executor is shut down on `stop()`.
- `ContendObserver` (ADR 0005) receives contention, ownership, watchdog and work events; observer failures are logged
  and ignored. New backend factories/services take it as a trailing optional parameter.

### Time
- Lease decisions use backend time or monotonic offsets — never wall clocks mixed across nodes.
- `MutexOwner` records `observedAt` (backend time) plus a monotonic anchor; `currentAt` advances it with
  `System.nanoTime()`. JDBC observes database time (`current_at`). Redis derives `transitionAt` from the local clock
  plus the key's `PTTL`, and `currentAt` defaults to the local wall clock.

### Wire contracts (nodes of different versions may run together)
- Redis names are built in both Kotlin (`RedisMutexKeys`) and Lua (`simba-spring-redis/src/main/resources/mutex_*.lua`):
  `simba:{mutex}` (lease key and channel), `simba:{mutex}:fence`, `simba:{mutex}:token`. Pub/sub messages are
  `{event}@@{ownerId}`; scripts take keys via `KEYS` and return arrays. Simba 4.0 requires every node on 3.2+.
- JDBC schema (MySQL-specific SQL):
  `simba_mutex(mutex, acquired_at, ttl_at, transition_at, owner_id varchar(128), version, fencing_token)`.
  Existing tables get `fencing_token` from `upgrade-simba-mysql-fencing-token.sql`.

## Change Playbooks

- **Contention loop:** change `LeaseContendService` and cover the race deterministically in `LeaseContendServiceTest`
  (manual scheduler and I/O executor; no real threads or sleeps).
- **New polling backend:** implement `MutexLeaseStore` plus a factory and extend `MutexContendServiceSpec`. Do not
  re-implement scheduling or generation checks in the backend.
- **Redis Lua / naming:** change `RedisMutexKeys`, `SpringRedisMutexLeaseStore`, `AcquireResult` / `OwnerEvent` and
  all three scripts together; keep mixed-version nodes working or document the upgrade order.
- **JDBC SQL / schema:** only compatible widening without asking. Update the init script, the schema snippet in
  `wiki/guide/backends.md` (EN and ZH), and check DB time vs JVM time.
- **Starter:** each backend activates on `simba.enabled` and `simba.<backend>.enabled` (both default `true`) and on
  `simba.backend` (unset, or naming it), plus its bean conditions. `SimbaAutoConfiguration` fails startup when several
  backends are active without `simba.backend`. Keep `META-INF/spring/...AutoConfiguration.imports` and
  `additional-spring-configuration-metadata.json` in sync with the properties.

## Conventions

- Package `me.ahoo.simba...`, four-space indentation, Apache license header, concise KDoc on public types.
- Constructor injection with immutable parameters; validate arguments at construction.
- Logging: `io.github.oshai.kotlinlogging.KotlinLogging` with lazy messages that include `mutex` and `contenderId`.
- No exposed mutable state: no public setters or public `AtomicXxxFieldUpdater`s in new code.
- Tests: JUnit Platform. New assertions use FluentAssert `.assert()`; keep existing Hamcrest assertions unless the test
  is already being changed. MockK only where a real code path is impractical. Name tests after the behavior;
  synchronize on latches/futures, never `sleep`.

## Git Workflow

- Commit and PR titles follow Conventional Commits (`type(scope)!: summary`, see `CONTRIBUTING.md`); the `Labeler`
  workflow enforces the title and applies the labels that drive release notes.
- Never push to `main`; use a branch and a PR. Stage only task-relevant files.
- Before pushing: targeted module checks plus `git diff --check`. A full `./gradlew check` needs only Docker.

## Boundaries

- **Always:** keep `README.md` / `README.zh-CN.md` and the wiki EN/ZH pages in sync.
- **Ask first:** public API changes in `simba-core` (including `MutexOwner` fields/equality); Redis Lua results,
  messages, or key names; JDBC SQL semantics or schema beyond compatible widening; default executors or thread model;
  starter activation semantics; new dependencies; CI, publication, or signing changes; wiki navigation.
- **Never:** commit secrets, `build/`, `out/`, `logs/`, IDE metadata, or generated artifacts.

## Documentation Map

| Path | Content |
|---|---|
| `README.md`, `README.zh-CN.md`, `llms.txt` | Entry points: what Simba is, install, one example per API (`llms.txt` mirrors `wiki/llms.txt`) |
| `wiki/` | VitePress site, the authoritative docs; page ownership and rules in `wiki/AGENTS.md` |
| `docs/adr/` | ADRs: 0001 lease engine, 0002 fencing token, 0003 Simba 4, 0004 leader scheduling, 0005 observability, 0006 Actuator endpoint, 0007 lease validity and health |
| `CONTRIBUTING.md`, `SECURITY.md`, `.github/` | Contribution workflow, quality gates, SemVer and releases; vulnerability reporting; templates, labeler, CODEOWNERS |
| `skills/simba/`, `skills/simba-testing/` | Source skill docs; do not generate marketplace artifacts here |
