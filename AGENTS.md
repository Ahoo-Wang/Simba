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
| `simba-core`, `simba-testing`, `simba-tck`, `simba-coroutines`, `simba-zookeeper`, `simba-spring-boot-starter` | Nothing (Zookeeper tests use embedded Curator `TestingServer`) |
| `simba-example` | Docker for `-PexampleBackend=jdbc` (`mysql:8.4`) and `redis` (`redis:7.4-alpine`); nothing for `zookeeper` |
| `simba-redis` (tests also cover `simba-spring-redis`) | Docker: Testcontainers `redis:7.4-alpine` (`RedisFixture`) |
| `simba-jdbc` | Docker: Testcontainers `mysql:8.4` and `postgres:17` (`LeaseDatabaseFixture`, schema from `simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-*.sql`) |

CI (`.github/workflows/integration-test.yml`; job names are required checks, keep them): `Simba Core Test` (core,
testing, tck, coroutines, starter), `Simba Spring Redis Test` (simba-redis and simba-spring-redis),
`Simba Zookeeper Test`, `Simba Jdbc Test`, `Simba Example (jdbc|redis|zookeeper)`, `Simba Test (JDK 25)`. Wiki commands are in
`wiki/AGENTS.md`.

## Architecture

### Modules

```
simba-core  ←  simba-jdbc | simba-redis ← simba-spring-redis | simba-zookeeper  ←  simba-spring-boot-starter
simba-testing (in-memory backend, virtual time, simulation)    simba-tck (LeaseBackendSpec, FencingSpec)
simba-coroutines (suspending lock calls, withLock, SimbaEventFlow, Term.awaitRevoked; kotlinx-coroutines-core)
simba-example (per-backend demo app), simba-bom, simba-dependencies, code-coverage-report
```

- `simba-dependencies` (never published) pins versions at build time only (Spring Boot BOM, kotlin-logging,
  Curator, kotlinx-coroutines): library modules take it through a non-published `versionPlatform` configuration, and
  their POM / module metadata carry the resolved versions (`versionMapping`), so consumers get no Spring Boot
  constraints. Test and build tool versions (MockK, Hamcrest, Testcontainers, detekt) come from the version catalog in
  the root build.
- Libraries are compiled for Kotlin API / language / stdlib 2.2 (`KOTLIN_BASELINE`), whatever compiler builds them;
  `versionPlatform` pins kotlin-stdlib / kotlin-reflect strictly to it, so publications ask for 2.2, not Boot's newer one.
  Published jars carry `Automatic-Module-Name` (`me.ahoo.simba.<module>`), `META-INF/LICENSE`, and the Dokka HTML in
  the javadoc jar; POM name and description come from each module's `description`. `-PuseMavenLocal` adds
  `mavenLocal()` after Maven Central.
- `simba-example` tests start the app with `SpringApplication` on the backend of `-PexampleBackend` (MySQL or Redis
  through Testcontainers, embedded Zookeeper).

- `simba-core` knows no backend and depends only on kotlin-logging. Packages: `me.ahoo.simba` (public API:
  `Simba`, `SimbaConfig`, `Election`, `LeadershipListener`, `Term`, `RevokeReason`, `LeaderTask`, `Schedule`,
  `DistributedLock`, `LockTerm`), `me.ahoo.simba.partition` (`PartitionGroup`, `GroupTask`, `PartitionAssignment`),
  `me.ahoo.simba.event` (`SimbaEvent`, `SimbaEventListener`, `SimbaSnapshot`), `me.ahoo.simba.spi` (`LeaseBackend`,
  `LeaseName`, `SessionId`, results), `me.ahoo.simba.engine` (`@InternalSimbaApi`, opt-in, not public API).
- The public API and SPI must stay usable from Java: no value classes in signatures (they mangle member names; check
  with `javap`), Kotlin default arguments get an overload or builder (`SimbaConfig.builder()`,
  `Term.ensureValid()`), blocking methods declare `@Throws(InterruptedException::class)`. `JavaInteropTest`
  implements `LeaseBackend` in Java.
- Public value types (`SimbaConfig`, `SimbaEvent.*`, the snapshots, the SPI results and hints `Capabilities`,
  `SessionGrant`, `KeepAliveResult.Live`, `AcquireResult.*`, `BackendHint.*`, and `SimulationParams`,
  `SimulationResult`, `InMemoryLeaseBackend.Owner`) are plain classes with hand-written `equals` / `hashCode` /
  `toString`, never data classes: no `copy` or `componentN`, so properties can be added compatibly. `SimbaConfig`
  and `SimulationParams` have no positional public constructor, only `builder()` / `toBuilder()` (and the defaults
  `SimulationParams()`).
- `simba-redis` holds the Redis backend and its Lua scripts behind the driver-neutral `RedisScripting` port; it must
  not depend on Spring. `simba-spring-redis` only adapts Spring Data Redis (`SpringRedisScripting`).
- `simba-tck`: `LeaseBackendSpec` every backend must pass, and `FencingSpec`, the kit users run against their own
  token-checking resource (optionally with real terms of a backend).
- `simba-coroutines` is the only module that depends on kotlinx-coroutines; `simba-core` must not.
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
   measured by the backend's clock. Keep-alive reports every owned lease (`held` is only a hint); inputs are validated
   alike (`LeaseBackend.requireLease` ≥ 1 ms, `requireGroup` = `LeaseName` rules, `requireBatch` ≤ `MAX_BATCH` 64).
   `InMemoryLeaseBackend(selfExpiringLeases = true)` models Redis-style leases (a grant lasts one full lease; with
   `sessionBoundGrants = true`, as on Redis, only what the session has left); `SimulationSpec` runs all three modes.
   Within 5.x the public API stays source compatible (Kotlin and Java callers keep compiling); binary compatibility is
   not required.

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
  thread per call) with a timeout of the renew period in effect (`Effect.Call.timeout`: shorter than the renew
  interval when the backend grants less than the lease); at most one keep-alive per session and one acquire per
  election are in flight.
- Callbacks run on one serial dispatcher per election over the callback executor (default virtual threads); the
  engine never waits for a callback except through the futures of `stop()` / `stepDown()`. `close()` and
  `LockTerm.close()` do not wait when called from a callback or leader work (they would wait for themselves).
- Work runs once per term on a task of the work executor (virtual threads), interrupted on revocation; failures
  (any `Throwable`) become `SimbaEvent.WorkCompleted` and never touch the election, except a `VirtualMachineError`:
  it ends the term's work, the election steps down for one lease duration and the error is rethrown to the work
  thread. Schedules loop on that one task, call `ensureValid` before each run and survive other `Error`s.
- Names are claimed per client under one lifecycle lock: claim-and-start and remove-and-stop are atomic, so the loop
  sees every stop before the next start of the name, and the shell never swaps the listener of a started election. A
  `LeaderTask` or `LockTerm` stops only its own run (closing it again never touches a later run of the name).
- `lock(name)`: one holder per name per client; a lock term lost other than by release is released for good, not
  re-acquired, but `acquire(timeout)` keeps contending until its timeout when the term it won ended before it
  returned. `acquireAsync` / `tryAcquireAsync` wait without a thread and complete on the callback executor, marked as
  a callback (`close()` in a dependent does not wait), and never with a term that already ended (the lock contends
  again); `LockTerm.releaseAsync()` completes the same way. The blocking calls check the loop before starting an
  attempt and wait on it. Cancelling one (or interrupting a blocking caller) abandons the attempt, releases a term it
  already won and ends its waits, so nothing stays registered on the client's close future (`footprint()`
  `closeWaiters`). A blocking `close()` of a stage already complete returns at once, on the loop too. Timers that are
  no longer needed (lock deadlines, schedule wake-ups, call timeouts) are cancelled.
- Groups: the engine joins the group with every session and publishes member views (`Effect.Members`); each node runs
  the partitions `PartitionAssignment` gives it over the session ids and hands the others over. Leaving while a join
  is in flight sends the leave once the join answers (even a failed one); a failed leave retries; the membership of
  an ended session ends with it. Joining again while leaving forgets the membership and the published view (the new
  joiner has seen none): a pending leave joins anew whatever it answers (a failed reply may hide a leave that went
  through), and without one the group joins at once. A members view without the session it was joined with (a timed-
  out leave that reached the backend after the rejoin) joins again. `GroupTask.stop()` also waits for partitions still handing over.
- Events are delivered in order on one serial dispatcher over the callback executor, never on the loop, and
  `close()` waits for them; listener failures are logged and ignored. On a loop thread (reachable only with a direct
  callback executor) `snapshot()` / `footprint()` read in place and blocking calls (`acquire`, blocking `close()`,
  `awaitIdle()`) throw `IllegalStateException` instead of deadlocking, and every stage the client returns is a
  `LoopGuardedStage` whose `toCompletableFuture()` throws there while pending (the client itself uses the raw
  futures). A rejecting callback executor never strands a future: completions run on the caller, events are
  delivered inline; a rejected `onElected` reports the work exited and steps the election down for one lease
  duration, and that term's `onRevoked` is never delivered (pairing holds). `Simba.awaitEvents()`
  (`@InternalSimbaApi`) waits for event delivery in tests.
- Renewal adapts to the backend: when it grants less than `leaseDuration` and two keep-alives at `renewInterval` no
  longer fit in the local validity (e.g. Zookeeper's `maxSessionTimeout`), the session renews every third of that
  validity and `SessionLeaseShortened` is published once. Closing publishes empty group views.

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
  in the same slot (`:leases` is a zset of lease keys scored by the acquire attempt's Redis time); keep-alive, acquire
  and join fail once the session hash's `PTTL` is 0 (Redis still keeps the key in that millisecond, so a bare
  `PEXPIRE` would revive it). Leases expire on their own: a new term lasts as long as its session, acquire by
  the holder extends it, and keep-alive extends and reports every indexed lease the
  session holds (never only `held`). Index entries leave only atomically on a single node, or on a Cluster once not
  held and unchanged for a lease. Close: delete session, free indexed leases and groups, drop indexes (retry-safe).
  Groups: zset `simba:group:{g}` scored by expiry.
  Release publishes `released` on the lease key, the only broadcast. With `routeBySlot` (Cluster) every script call
  keeps to one hash slot. Only durations cross nodes, never a node's `TIME`: every call after the session-slot one
  carries what the session has left (its remaining time minus the client's monotonic time since that call
  answered), and its node grants or extends nothing beyond its own `now` plus that (also the single-node second
  keep-alive call). Acquire and join send nothing once the session's remaining time passed since the session call
  was sent; an acquire answering later than that releases the terms it began and fails. Group names follow the `LeaseName` rules. `wait-replicas` makes a new term wait for `WAIT`.
- Zookeeper (`ZookeeperLeaseBackend`): one raw `ZooKeeper` client per session, never reconnected to another session;
  `SessionId` = zk session id as 16 hex digits. Leases are ephemeral `/simba/leases/{name}`, owner = `ephemeralOwner`,
  token = czxid (strictly increasing, not consecutive). Members are ephemeral `/simba/groups/{g}/{sid}`. A local
  watchdog closes a session no keep-alive started within `lease`; remaining is `min(lease, negotiated timeout)`. A
  disconnected client fails `keepAlive` / `acquire`; a zk-expired one is `Expired`, and so is a `CONNECTIONLOSS` of a
  closing client (Zookeeper fails requests that way while closing, before the state turns `CLOSED`).
  A name is a keep-alive candidate from before its create is sent (under the session's send lock, so a keep-alive read
  sent earlier cannot drop it); keep-alive reports candidates the session owns and forgets the others. Releases of a
  session are serialized (the czxid check and the delete are not atomic). Lease paths of a batch are validated before
  any request; `members` syncs the group path, then leaves a children watch (an exists watch on a missing group). A
  closed backend opens no session. Group names follow the `LeaseName` rules. Every client adds `authInfo` and uses
  `clientConfig`; every node the backend creates gets `aclProvider`'s ACL (default open; existing parents keep theirs, also
  when the backend may not create in their parent: `NoAuth` on an existing ancestor is ignored).

### Starter
- Properties: `simba.enabled`, `simba.backend`, `simba.session.{lease-duration,renew-interval,drift,margin}`,
  `simba.jdbc.{enabled,dialect,query-timeout}`, `simba.redis.{enabled,wait-replicas,wait-timeout,route-by-slot}`,
  `simba.zookeeper.{enabled,connect-string,root,connection-timeout,auth.scheme,auth.credentials,acl}`,
  `simba.scheduling.enabled`, `simba.metrics.enabled`.
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
  `SimulationSpec` (I1–I5 over 10⁴ seeds, four backend modes: owned, self-expiring, session-bound, no hints) green.
  The simulation varies node clock rates over time (beyond `drift`, within what `margin` covers), truncates grants,
  expires sessions, deletes and loses leases backend-side (those terms are exempt from I1/I2 until revoked; a lost
  lease restarting tokens counts as `premise:tokenReset`, not a failure), drives a partition group and closes nodes
  mid-fault. Its checks read the backend without side effects (`peekOwner` / `peekExpiry`): I1 also compares each
  valid term's deadline in base time with the backend expiry and fails a term that becomes valid again; I4 covers
  partitions and leaked leases. `every fault path actually happens` asserts each path (`path:*` in
  `SimulationResult.events`, every `RevokeReason`) is reached; mutants of the engine (adoption past the deadline,
  revival by a late renewal, no margin, no orphan release, partitions giving up, a lease of extra delay) are caught. Threaded-client tests run on virtual time: `VirtualScheduler`, direct
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
