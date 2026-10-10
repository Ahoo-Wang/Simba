# Simba - Agent Instructions

Simba is a JVM distributed mutex / leader-election library. It guarantees that, per mutex, at most one
contender *observes itself* as owner of a time-bounded lease, and it delivers ordered acquire/release callbacks.
It does **not** provide fencing tokens: local ownership is a weakly consistent view of the backend.

This file records invariants and traps that are not obvious from the code. Describe the code as it is,
not as it is planned to be; design direction belongs in `docs/adr/`.

## Commands

```bash
./gradlew build                                   # Build all modules
./gradlew <module>:check                          # Tests + detekt (autoCorrect) for one module
./gradlew <module>:test --tests <fully.qualified.TestClass>
./gradlew simba-example:check -PexampleBackend=jdbc|redis|zookeeper   # default: redis
./gradlew codeCoverageReport                      # Aggregated JaCoCo report
./gradlew check -PtestJavaVersion=25              # Run tests on another JDK (bytecode stays 17)
```

| Module check | Needs |
|---|---|
| `simba-core`, `simba-zookeeper` | Nothing (Zookeeper uses embedded Curator `TestingServer`) |
| `simba-spring-redis` | Docker (Testcontainers `redis:7.4-alpine` via `RedisFixture`) |
| `simba-jdbc` | Docker (Testcontainers `mysql:8.4` via `MySqlFixture`, initialized with `simba-jdbc/src/init-script/init-simba-mysql.sql`) |

CI (`.github/workflows/integration-test.yml`) runs `simba-core` first, then the Redis, Zookeeper, JDBC module
checks and the `simba-example` backend matrix. Wiki commands: see `wiki/AGENTS.md`.

## Modules

`simba-core` ← `simba-jdbc` / `simba-spring-redis` / `simba-zookeeper` ← `simba-spring-boot-starter`.
`simba-core` must not know any backend and depends only on kotlin-logging; backend-specific behavior stays in its
module. `simba-test` is the backend TCK (`MutexContendServiceSpec`). `simba-bom` / `simba-dependencies` are
publication metadata.

## Core Invariants

### Lease model
- An owner holds `ttlAt` (renew deadline) and `transitionAt = ttlAt + transition` (grace period). Inside the
  grace period only the current owner may renew; others may acquire only after `transitionAt`.
- `ContendPeriod`: owners retry at `ttlAt`; contenders retry at `transitionAt` plus jitter in `[-200, 1000)` ms
  (`[0, 1000)` when transition is zero).
- JDBC: one `simba_mutex` row per mutex; the acquire `UPDATE` encodes the rule above using database time.
- Redis: `simba:{mutex}` with `PX = ttl + transition`; acquire is `SET NX`, renew (guard) is `SET XX` by the
  owner only; release deletes the key and broadcasts `released` on the mutex channel so every contender contends.
- Zookeeper: Curator `LeaderLatch` at `/simba/{mutex}`; ttl/transition do not apply (`ttlAt = transitionAt = MAX`).
- Fencing tokens (ADR 0002): `MutexOwner.fencingToken` increases strictly per ownership term and stays stable within
  it; `0` means none. Zookeeper uses the winning latch node's czxid, never its sequence (container parents get reaped
  and restart sequences). Redis: `INCR simba:{mutex}:fence` on `SET NX`, term token in `simba:{mutex}:token`; nodes
  ignore broadcasts of their own acquisition. JDBC (`simba.jdbc.fencing`, on by default): `fencing_token` advanced in
  the acquire `UPDATE` only on a new term; that assignment must stay first in `SET` (MySQL evaluates left to right).
- JDBC and Redis share the polling loop in `LeaseContendService`; a backend only implements `MutexLeaseStore`
  (one atomic call, no scheduling or notification). `LeaseConfig` is the single place for duration validation.

### Lifecycle
- `Status`: `INITIAL → STARTING → RUNNING → STOPPING → INITIAL`. A failed `start()` returns to `INITIAL`;
  services are restartable with the same `contenderId`.
- `stop()` always delivers a release (`MutexOwner.NONE`) notification, even if `stopContend()` throws.
- Each `start()` begins a new generation. Notifications from an older generation are dropped; while inactive,
  only `NONE` may be applied.
- A late acquisition finishing after `stop()` must be released remotely, but not if a restarted lifecycle with
  the same `contenderId` is now active (that would release the new lease). `LeaseContendService.adopt` owns this.
- Local lease guard (`LeaseContendService`): each successful acquire/renew arms a watchdog at `transitionAt`,
  measured from when the call was sent; it revokes local ownership if no renewal succeeds by then, even while
  a backend call hangs. A failed renew keeps ownership while the lease is valid and retries with a halving
  backoff (min 100 ms); otherwise it revokes and retries after `ttl`. With `transition = 0` ownership can flap.
- The starter sets `JdbcMutexOwnerRepository.queryTimeout` to `simba.jdbc.ttl`.
- `close()` is idempotent (stops only when `RUNNING`); `stop()` still throws when not `RUNNING`.

### Threading
- Owner notifications run asynchronously on a sequential executor over `handleExecutor`
  (library default: `ForkJoinPool.commonPool()`; the starter injects the dedicated `simbaHandleExecutor` bean).
  The state lock is never held while callbacks run; `SequentialExecutor` keeps them ordered. `stop()` returns only
  after its own `onReleased` ran (inline when called from a callback), so callers of `stop()` must not hold locks
  that their callbacks take. Keep callbacks short.
- `LeaseContendService` splits a trigger `ScheduledExecutorService` (never blocks) from an `ioExecutor` running
  `MutexLeaseStore` calls, with at most one call in flight per service and lifecycle. JDBC and Redis factories own
  shared executors from `ContendExecutors` (daemon, idle threads reclaimed) and shut them down on `close()`.
  A directly constructed service defaults to its own idle-reclaimed scheduler and runs I/O on the trigger thread.
- `SimbaLocker` is owned by one thread at a time; interruption does not cancel `acquire()` (the flag is restored).
- `SimbaScheduler` and `AbstractScheduler` share `ScheduledWorkRunner`: the worker executor is created on acquire,
  work is cancelled with interrupt on release, and the executor is shut down on `stop()`.

### Time
- Lease decisions must use backend time or monotonic offsets, never mixed wall clocks across nodes.
- `MutexOwner` records `observedAt` (backend time) and a monotonic anchor; `currentAt` advances it with
  `System.nanoTime()`. JDBC observes database time (`current_at`).
- Redis derives `transitionAt` from the local clock plus the key's `PTTL`; `MutexOwner.currentAt` defaults to
  the local wall clock.

### Wire contracts (compatibility-sensitive; nodes of different versions may run together)
- Redis key and channel names are built in both Kotlin (`RedisMutexKeys`) and Lua: `simba:{mutex}` (lease and
  channel), `simba:{mutex}:fence`, `simba:{mutex}:token`. 4.0 requires every node to run Simba 3.2+. Pub/sub messages
  use `{event}@@{ownerId}`; Lua scripts take keys via `KEYS` and return arrays.
- JDBC schema: `simba_mutex(mutex, acquired_at, ttl_at, transition_at, owner_id varchar(128), version, fencing_token)`;
  the column is added to existing tables by `upgrade-simba-mysql-fencing-token.sql`. The SQL is
  MySQL-specific.

## Change Playbooks

- **Contention loop:** change `LeaseContendService` and cover the race deterministically in
  `LeaseContendServiceTest` (manual scheduler and I/O executor, no threads or sleeps).
- **New polling backend:** implement `MutexLeaseStore` plus a factory; extend `MutexContendServiceSpec`.
  Do not re-implement scheduling or generation checks in the backend.
- **Redis Lua / naming:** change `RedisMutexKeys`, `SpringRedisMutexLeaseStore`, `AcquireResult` / `OwnerEvent`
  and all three scripts together; keep mixed-version nodes working or document the upgrade order.
- **JDBC SQL / schema:** only compatible widening without asking; update the init script, README/wiki schema
  snippets, and consider DB time vs JVM time.
- **Starter:** each backend activates on `simba.enabled`, `simba.<backend>.enabled` (both default `true`) and
  `simba.backend` (unset or naming it), plus its bean conditions; `SimbaAutoConfiguration` fails startup when several
  backends are active without `simba.backend`. Keep `META-INF/spring/...AutoConfiguration.imports` and
  `additional-spring-configuration-metadata.json` in sync with properties.

## Code Conventions

- Package `me.ahoo.simba...`, four-space indentation, Apache license header, concise KDoc on public types.
- Constructor injection, immutable constructor parameters; validate arguments at construction.
- Logging: `io.github.oshai.kotlinlogging.KotlinLogging` with lazy messages including `mutex` and `contenderId`.
- Do not expose mutable state: no public setters or public `AtomicXxxFieldUpdater`s on new code.
- Shared API types live in `simba-core`; do not leak backend details into core abstractions.

## Testing

- JUnit Platform, Kotlin/JVM, Java 17. New assertions use FluentAssert `.assert()`; keep existing Hamcrest
  assertions unless the test is already being changed. Use MockK only where a real code path is impractical.
- Name tests after the behavior. Synchronize on latches/futures, not `sleep`.

## Git Workflow

- Commit and PR titles follow Conventional Commits (`type(scope)!: summary`, see `CONTRIBUTING.md`); the `Labeler`
  workflow enforces the title and applies the labels that drive release notes.
- Never push to `main`; use a branch and a PR. Stage only task-relevant files.
- Before pushing: targeted module checks plus `git diff --check`; full `./gradlew check` needs only Docker.

## Boundaries

- **Always:** keep `README.md` / `README.zh-CN.md` and wiki EN/ZH in sync; update this file when an invariant
  above changes.
- **Ask first:** public API changes in `simba-core` (including `MutexOwner` fields/equality); Redis Lua results,
  messages, or key names; JDBC SQL semantics or schema beyond compatible widening; default executors or thread
  model; starter activation semantics; new dependencies; CI, publication, or signing changes; wiki navigation.
- **Never:** commit secrets, `build/`, `out/`, `logs/`, IDE metadata, or generated artifacts.

## Documentation Map

| Path | Content |
|---|---|
| `README.md`, `README.zh-CN.md`, `llms.txt` | User-facing entry points |
| `wiki/` | VitePress site (authoritative docs); rules in `wiki/AGENTS.md` |
| `docs/adr/` | Architecture decision records |
| `CONTRIBUTING.md`, `SECURITY.md`, `.github/` | Contribution workflow, quality gates, SemVer and release process; vulnerability reporting; templates, labeler, CODEOWNERS |
| `skills/simba/`, `skills/simba-testing/` | Source skill docs; do not generate marketplace artifacts here |
