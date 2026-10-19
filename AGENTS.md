# Simba - Agent Instructions

Simba is a JVM (Kotlin, Java 21 bytecode) distributed lease library over JDBC (MySQL 8, PostgreSQL 14+), Redis 7 or
Zookeeper (ADR 0010). Its whole contract: per lease name, at most one node holds a *valid* `Term` at a time (as far as
its local clock can tell), every term carries a fencing token that strictly increases per name, and every
`onElected(term)` is followed by exactly one `onRevoked(term, reason)`.

That view is weakly consistent. A paused process can still act after its term ended; only a protected resource that
checks the fencing token can reject a stale owner. Never document or implement anything that promises more (no "no
split-brain", no work continuing while the backend is unreachable).

This file records what is not obvious from the code. Describe the code as it is; design direction belongs in
`docs/adr/`. When an invariant below changes, change this file in the same PR.

## Commands

```bash
./gradlew build                                          # Build all modules
./gradlew <module>:check                                 # Tests + detekt (autoCorrect rewrites sources)
./gradlew <module>:test --tests <fully.qualified.TestClass>
./gradlew simba-example:check -PexampleBackend=jdbc|redis|zookeeper   # default: redis
./gradlew test -PtestJavaVersion=25                      # Tests on another JDK; bytecode stays 21
./gradlew simba-testing:test -Psimba.simulation.seed=N   # Replay one SimulationSpec seed (-Psimba.simulation.seeds=N: more)
./gradlew codeCoverageReport                             # Aggregated JaCoCo report
```

| Module check | Needs |
|---|---|
| `simba-core`, `simba-testing`, `simba-tck`, `simba-zookeeper`, `simba-spring-boot-starter` | Nothing (Zookeeper tests use embedded Curator `TestingServer`) |
| `simba-example` | Docker for `-PexampleBackend=jdbc` (`mysql:8.4`) and `redis` (`redis:7.4-alpine`); nothing for `zookeeper` |
| `simba-redis` (tests also cover `simba-spring-redis`) | Docker: Testcontainers `redis:7.4-alpine` (`RedisFixture`) |
| `simba-jdbc` | Docker: Testcontainers `mysql:8.4` and `postgres:17` (`LeaseDatabaseFixture`, schema from `simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-*.sql`) |

CI (`.github/workflows/integration-test.yml`; job names are required checks, keep them): `Simba Core Test` (core,
testing, tck, starter), `Simba Spring Redis Test` (simba-redis and simba-spring-redis), `Simba Zookeeper Test`,
`Simba Jdbc Test`, `Simba Example (jdbc|redis|zookeeper)`, `Simba Test (JDK 25)`. Wiki commands are in
`wiki/AGENTS.md`.

## Architecture

### Modules

```
simba-core  ←  simba-jdbc | simba-redis ← simba-spring-redis | simba-zookeeper  ←  simba-spring-boot-starter
simba-testing (in-memory backend, virtual time, simulation)    simba-tck (LeaseBackendSpec, FencingSpec)
simba-example (per-backend demo app), simba-bom, simba-dependencies, code-coverage-report
```

- `simba-dependencies` is published as every library's platform: only the Spring Boot BOM and runtime constraints
  (kotlin-logging, Curator). Test and build tool versions (MockK, Hamcrest, Testcontainers, detekt) come from the
  version catalog in the root build and are not transitive.
- `simba-example` tests start the app with `SpringApplication` on the backend of `-PexampleBackend` (MySQL or Redis
  through Testcontainers, embedded Zookeeper).

- `simba-core` knows no backend and depends only on kotlin-logging. Packages: `me.ahoo.simba` (public API:
  `Simba`, `SimbaConfig`, `Election`, `LeadershipListener`, `Term`, `RevokeReason`, `LeaderTask`, `Schedule`,
  `DistributedLock`, `LockTerm`), `me.ahoo.simba.partition` (`PartitionGroup`, `GroupTask`, `PartitionAssignment`),
  `me.ahoo.simba.event` (`SimbaEvent`, `SimbaEventListener`, `SimbaSnapshot`), `me.ahoo.simba.spi` (`LeaseBackend`,
  `LeaseName`, `SessionId`, results), `me.ahoo.simba.engine` (`@InternalSimbaApi`, opt-in, not public API).
- The public API and SPI must stay usable from Java: no value classes in signatures (they mangle member names; check
  with `javap`), Kotlin default arguments get an overload or builder (`SimbaConfig.builder()`,
  `Term.ensureValid()`). `JavaInteropTest` implements `LeaseBackend` in Java.
- `simba-redis` holds the Redis backend and its Lua scripts behind the driver-neutral `RedisScripting` port; it must
  not depend on Spring. `simba-spring-redis` only adapts Spring Data Redis (`SpringRedisScripting`).
- `simba-tck`: `LeaseBackendSpec` every backend must pass, and `FencingSpec`, the kit users run against their own
  token-checking resource (optionally with real terms of a backend).
- `simba-testing`: `InMemoryLeaseBackend`, `VirtualClock`, `VirtualScheduler`, `Simulation` for users' unit tests.

### Layers (top to bottom)

1. **User APIs**: `Simba` (one per process: one backend session) → `election(name)` (`start(listener)`, `stop()`,
   `stepDown(cooldown)`, `runWhileLeader`, `schedule`), `lock(name)` (`acquire(timeout)` / `tryAcquire()` →
   `LockTerm`), `group(name, partitions)` (`schedule` / `runWhileLeader` per partition), `snapshot()`. The starter
   adds the `Simba` bean, `@SimbaScheduled`, `@SimbaPartitioned`, Micrometer metrics, the Actuator endpoint `simba`
   and the `simba` health indicator.
2. **Engine**: `engine.step(state, event, now)` is pure (no threads, clock or I/O); `EngineShell` runs it on one loop
   thread and executes its effects (backend calls, timers, callbacks, group views); `Simulation` runs it in virtual
   time.
3. **`LeaseBackend` SPI**: session open / keep-alive / close, batch acquire, release, group join / leave / members,
   optional hints. No scheduling, no notification, no local lease state; remaining durations come back as durations
   measured by the backend's clock.

## Invariants

### Session and terms
- One session per `Simba` client incarnation; a `SessionId` is never reused. An expired session is never revived:
  the engine opens a new one and contends again.
- Local deadline of a session = `sentAt + remaining × (1 − drift) − margin`, measured from when the keep-alive was
  *sent*. `SimbaConfig` validates `renewInterval × 2 ≤ leaseDuration` and that the local validity exceeds one renew
  interval. Defaults: lease 15 s, renew a third of it, drift 0.01, margin 50 ms.
- A term is adopted only from the reply to the election's current attempt, before the session's local deadline, and
  only with a token above every token the session released for that name. A term is revoked before its lease is
  released; releases carry the token, so a late one is a no-op.
- `Term.isValid` / `remaining()` are pure functions of the monotonic clock and the session's volatile deadline (no
  watchdog thread); a revoked term never becomes valid again, and a keep-alive reply arriving after the local
  deadline expires the session (`DEADLINE_PASSED`) instead of extending it. They are hints to start a step, never a
  substitute for the fencing check. Monotonic instants are compared by difference, never with `maxOf`.
- Keep-alive replies list the leases the session owns: a term adopted before the request but missing from the reply
  is revoked (`TAKEN_OVER`); an owned lease nobody holds or is acquiring is released.
- A hint is never dropped because its request is in flight (the reply may predate the change): a release hint makes a
  losing in-flight attempt retry at once, a membership hint repeats an in-flight members read at once.
- Handover (ADR 0010 contract 3): an election started with work (`runWhileLeader`, `schedule`, groups) keeps the
  lease of a stopped or stepped-down term in `ElectionState.Handover` until `Event.WorkExited` for that term or the
  session's local deadline at the stop, whichever comes first; terms without work release at once. A step-down
  cooldown counts from the step-down. Terms lost otherwise (deadline, take-over, expiry) are not waited for.

### Threading
- The engine state is touched only by the loop thread. Backend calls run on the I/O executor (default: a virtual
  thread per call) with a timeout of one renew interval; at most one keep-alive per session and one acquire per
  election are in flight.
- Callbacks run on one serial dispatcher per election over the callback executor (default virtual threads); the
  engine never waits for a callback except through the futures of `stop()` / `stepDown()`. `close()` and
  `LockTerm.close()` do not wait when called from a callback or leader work (they would wait for themselves).
- Work runs once per term on a task of the work executor (virtual threads), interrupted on revocation; failures
  become `SimbaEvent.WorkCompleted` and never touch the election. Schedules loop on that one task and call
  `ensureValid` before each run.
- Names are claimed per client under one lifecycle lock: claim-and-start and remove-and-stop are atomic, so the loop
  sees every stop before the next start of the name, and the shell never swaps the listener of a started election. A
  `LeaderTask` or `LockTerm` stops only its own run (closing it again never touches a later run of the name).
- `lock(name)`: one holder per name per client; a lock term lost other than by release is released for good, not
  re-acquired, but `acquire(timeout)` keeps contending until its timeout when the term it won ended before it
  returned. `acquireAsync` / `tryAcquireAsync` wait without a thread and complete on the callback executor; the
  blocking calls wait on them, and cancelling one (or interrupting a blocking caller) abandons the attempt and releases
  a term it already won. Timers that are no longer needed (lock deadlines, schedule wake-ups, call timeouts) are cancelled.
- Groups: the engine joins the group with every session and publishes member views (`Effect.Members`); each node runs
  the partitions `PartitionAssignment` gives it over the session ids and hands the others over. Leaving while a join
  is in flight sends the leave once the join answers (even a failed one); a failed leave retries; the membership of
  an ended session ends with it. `GroupTask.stop()` also waits for partitions still handing over.
- `SimbaEventListener` failures are logged and ignored; events are published from the loop thread.

### Observability
- `Simba.snapshot()` is read on the loop: session id, status (`OPENING`, `LIVE`, `SUSPENDED`, `CLOSED`), `validFor`,
  `notLiveFor`, and per election status, token, remaining and `kind` (`ElectionKind`: `ELECTION`, `LOCK`, `GROUP`,
  also on `Elected` / `Revoked`). Close publishes `SessionClosed` once. `healthy` = not live for at most `leaseDuration`; the
  starter's health indicator is exactly that rule.
- Micrometer (starter): `simba.session.renew` (timer), `simba.session.state` (gauge per `state`, `closed` from
  `SessionClosed`), `simba.leadership` (gauge per `name`, elections and partitions only, removed when a term of the
  name ends `STOPPED`), `simba.locks.held` (gauge, no tags), `simba.revocations` (counter per `reason` and `kind`),
  `simba.work` (timer per `name`, `outcome`). Locks (`ElectionKind.LOCK`) are never tagged by name: their names come
  from data.

### Wire contracts
- Lease names (`LeaseName`): 1–128 characters of `[A-Za-z0-9._:-]`, not `.` or `..`. Partition `p` of group `g` is the lease
  `{g}.{p}`; `PartitionAssignment.weight` (FNV-1a 64 of `"$partition@$member"` mixed with SplitMix64, members = session
  ids) must stay identical across versions.
- JDBC (`JdbcLeaseBackend` + `Dialect`): tables `simba_session`, `simba_lease`, `simba_member`; every time comparison
  uses the dialect's `now` (database UTC epoch ms, constant within a statement, never through the session
  `time_zone`). `simba_lease` rows are never deleted (the token never resets); release clears `session_id` only. A
  lease belongs to its `session_id` only while that session is live. The MySQL acquire assigns `token` before
  `session_id` (left-to-right `SET`). Anything that frees a lease because its session expired (take-over, purge) must
  read that session row with a lock (`for share` / `for update`), never the statement snapshot: otherwise it can race a
  keep-alive that commits concurrently, at READ COMMITTED on both databases. Lease and group names are case-sensitive
  (binary collations). `purgeExpired()` runs at most once per `purgeInterval` after `openSession` / `keepAlive`.
- Redis (`me.ahoo.simba.redis.RedisLeaseBackend`, Lua in `simba-redis/src/main/resources/me/ahoo/simba/redis/`): lease
  hash `simba:lease:{name}` = `{session, token, expires}` never expires and is never deleted (release clears
  `session`); `expires` is Redis `TIME`. Session hash `simba:session:{sid}` (PX) plus `:leases` / `:groups` index sets
  in the same slot; keep-alive, acquire and join fail once it is gone. A new term lasts as long as its session and
  keep-alive extends only `held`, so leases expire on their own. Groups: zset `simba:group:{g}` scored by expiry.
  Release publishes `released` on the lease key, the only broadcast. With `routeBySlot` (Cluster) every script call
  keeps to one hash slot. Group names follow the `LeaseName` rules. `wait-replicas` makes a new term wait for `WAIT`.
- Zookeeper (`ZookeeperLeaseBackend`): one raw `ZooKeeper` client per session, never reconnected to another session;
  `SessionId` = zk session id as 16 hex digits. Leases are ephemeral `/simba/leases/{name}`, owner = `ephemeralOwner`,
  token = czxid (strictly increasing, not consecutive). Members are ephemeral `/simba/groups/{g}/{sid}`. A local
  watchdog closes a session no keep-alive started within `lease`; remaining is `min(lease, negotiated timeout)`. A
  disconnected client fails `keepAlive` / `acquire`; a zk-expired one is `Expired`.
  A name is a keep-alive candidate from before its create is sent (under the session's send lock, so a keep-alive read
  sent earlier cannot drop it); keep-alive reports candidates the session owns and forgets the others. Releases of a
  session are serialized (the czxid check and the delete are not atomic). Lease paths of a batch are validated before
  any request; `members` on a missing group leaves an exists watch. A closed backend opens no session.

### Starter
- Properties: `simba.enabled`, `simba.backend`, `simba.session.{lease-duration,renew-interval,drift,margin}`,
  `simba.jdbc.{enabled,dialect,query-timeout}`, `simba.redis.{enabled,wait-replicas,wait-timeout,route-by-slot}`,
  `simba.zookeeper.{enabled,connect-string,root,connection-timeout}`, `simba.scheduling.enabled`,
  `simba.metrics.enabled`.
- Each backend auto-configuration activates on `simba.enabled`, `simba.<backend>.enabled` (both default `true`) and
  `simba.backend` (unset, or naming it), and defines the `LeaseBackend` bean unless the application has one.
  `SimbaAutoConfiguration` fails startup when several backends are active without `simba.backend`, and builds the one
  `Simba` bean over the `LeaseBackend` with every `SimbaEventListener` bean.
- Shutdown order: `SimbaScheduledBeanPostProcessor` (phase `DEFAULT_PHASE`) stops `@SimbaScheduled` /
  `@SimbaPartitioned` work and waits for the handover; `SimbaLifecycle` (phase `DEFAULT_PHASE - 4096`) then closes the
  client, before lower-phase infrastructure such as connection factories stops.
- JDBC `query-timeout` defaults to the session renew interval (the engine gives up on a call after it anyway).
- The properties classes are Kotlin data classes bound through their primary constructor, which needs
  `kotlin-reflect` at runtime: the starter declares it (without it Boot falls back to JavaBean binding and fails on
  any non-default value). Tests must not rely on MockK bringing it in; `ExampleBackendTest` runs without MockK.
- The Redis backend requires a Lettuce `RedisConnectionFactory` (startup check): Jedis blocks on subscribe and lacks
  the native `WAIT`.
- `@SimbaScheduled` names, `@SimbaPartitioned` partition leases (`{group}.{p}`) and groups share one namespace,
  checked at startup.

## Change Playbooks

- **Engine:** change `step()` and cover it with `EngineTest`-style table tests (events in, effects out); keep
  `SimulationSpec` (I1–I5 over 10⁴ seeds) green. Threaded-client tests run on virtual time: `VirtualScheduler`, direct
  I/O and callback executors, `awaitIdle()` between timers (see `VirtualSimba`); never sleep. Work runs on real
  threads that `awaitIdle()` does not cover: wait for the work to start before stopping it (work revoked before it
  starts never runs), start contenders one at a time when a test needs a particular winner, and keep session ids
  deterministic (`VirtualSimba` uses `s1`, `s2`, …) wherever partition assignment matters. Repeat a new threaded
  test a few hundred times before merging.
- **New backend:** implement `LeaseBackend` and extend `LeaseBackendSpec`; a backend with an injectable clock
  overrides `clock`, otherwise time-based tests poll until the backend's own lease ran out. Add a starter
  auto-configuration, a `SimbaBackend` entry and an example backend.
- **Redis Lua / naming:** change `RedisLeaseKeys`, `RedisLeaseBackend` and the scripts together; every script call
  must stay within one hash slot under `routeBySlot`.
- **JDBC SQL / schema:** update both dialects and both init scripts; every time decision uses the database `now`.
- **Starter:** keep `META-INF/spring/...AutoConfiguration.imports`, `additional-spring-configuration-metadata.json`
  and `ConfigurationMetadataTest` in sync with the properties.

## Conventions

- Package `me.ahoo.simba...`, four-space indentation, Apache license header, concise KDoc on public types. Times in
  the API are `Duration`, never `Long` milliseconds.
- Constructor injection with immutable parameters; validate arguments at construction.
- Logging: `io.github.oshai.kotlinlogging.KotlinLogging` with lazy messages that include the lease name.
- No exposed mutable state: no public setters or public `AtomicXxxFieldUpdater`s in new code.
- Tests: JUnit Platform, FluentAssert `.assert()`. MockK only where a real code path is impractical. Name tests after
  the behavior; synchronize on queues, latches or futures, never `sleep`.

## Git Workflow

- Commit and PR titles follow Conventional Commits (`type(scope)!: summary`, see `CONTRIBUTING.md`); the `Labeler`
  workflow enforces the title and applies the labels that drive release notes.
- Never push to `main`; use a branch and a PR. Stage only task-relevant files.
- Before pushing: targeted module checks plus `git diff --check`. A full `./gradlew check` needs only Docker.

## Boundaries

- **Always:** keep `README.md` / `README.zh-CN.md` and the wiki EN/ZH pages in sync.
- **Ask first:** public API changes in `simba-core`; `LeaseBackend` SPI changes; Redis Lua results, messages, or key
  names; JDBC SQL semantics or schema; default executors or thread model; starter activation semantics; new
  dependencies; CI, publication, or signing changes; wiki navigation.
- **Never:** commit secrets, `build/`, `out/`, `logs/`, IDE metadata, or generated artifacts.

## Documentation Map

| Path | Content |
|---|---|
| `README.md`, `README.zh-CN.md`, `llms.txt` | Entry points: what Simba is, install, one example per API (`llms.txt` mirrors `wiki/llms.txt`) |
| `wiki/` | VitePress site, the authoritative docs; page ownership and rules in `wiki/AGENTS.md` |
| `docs/adr/` | ADRs: 0001–0009 record the 4.x design and are superseded by 0010 (Simba 5 architecture) where they conflict; 0002 (fencing token) still holds |
| `CONTRIBUTING.md`, `SECURITY.md`, `.github/` | Contribution workflow, quality gates, SemVer and releases; vulnerability reporting; templates, labeler, CODEOWNERS |
| `skills/simba/`, `skills/simba-testing/` | Source skill docs; do not generate marketplace artifacts here |
