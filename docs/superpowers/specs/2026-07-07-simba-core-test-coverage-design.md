# Design: simba-core Unit Test Coverage Improvement

**Date:** 2026-07-07
**Scope:** `simba-core` module only (no external services required)
**Goal:** Raise direct unit-test coverage of `simba-core` from ~80% line / weak branch coverage toward ~95%+ line coverage with comprehensive branch coverage, by adding focused unit tests for every source file with branch logic.

## 1. Problem Statement

`simba-core` is the most foundational module (public mutex abstractions, lifecycle state machine, scheduling, `SimbaLocker`). Despite its importance:

- It has **20 main source files but only 1 test file** (`ContendPeriodTest.kt`, covering a single branch of `nextOwnerDelay`).
- Its ~80% line coverage comes **indirectly** from downstream TCK tests in `simba-jdbc`/`simba-redis`/`simba-zookeeper` — not from in-module unit tests. This means the module cannot be validated in isolation.
- The aggregate **branch coverage is 51.4%**, the weakest metric across the project. Conditionals in the state machine, value-type predicates, and contender dispatch are under-exercised.
- Key classes have **0% direct coverage**: `AbstractMutexRetrievalService` (the lifecycle state machine), `Simba`, `SimbaException`, `UUIDContenderIdGenerator`, and the `MutexMessageListener`-adjacent core types.

This design fills those gaps with pure unit tests that run without Redis/MySQL/Zookeeper.

## 2. Design Principles

1. **Strict style alignment with `ContendPeriodTest.kt`:**
   - Hamcrest assertions (`MatcherAssert.assertThat` + `Matchers.*`), NOT JUnit `Assertions.assertEquals`.
   - JUnit Jupiter `@Test` (`org.junit.jupiter.api.Test`).
   - Backtick descriptive test method names (`` `owner delay uses owner clock` ``).
   - Apache 2.0 license header on every new file.
   - Package mirrors main source (`me.ahoo.simba.core`, `me.ahoo.simba.schedule`, etc.).
   - Class naming `<SubjectUnderTest>Test`.
2. **Fakes over mocks.** Provide minimal in-module subclasses (e.g. `FakeMutexContendService`) instead of MockK — matches TCK convention (`MutexContendServiceSpec` uses anonymous `object : AbstractMutexContender` subclasses, no mocks). MockK stays available but unused unless a fake is impractical.
3. **Deterministic time.** Reuse the `FixedClockOwner` pattern (subclass `MutexOwner`, override `currentAt`) for all time-dependent logic. Pass all 4 `MutexOwner` constructor args explicitly in tests.
4. **Deterministic async.** Use a synchronous `Executor { it.run() }` when driving `AbstractMutexRetrievalService.notifyOwner`, so `CompletableFuture.runAsync` runs inline and `safeNotifyOwner`'s try/catch is testable without waiting.
5. **No external services.** Every test runs in plain `./gradlew simba-core:test`. No Redis, MySQL, Zookeeper, Testcontainers.
6. **Concurrency safety.** `SimbaLocker.acquire()` blocks via `LockSupport.park` — unit-test the timeout and double-acquire branches directly; for the happy path, drive `onAcquired` from a separate thread with `CountDownLatch` awaitability (mirroring `MutexContendServiceSpec`). `AbstractScheduler` uses a real `ScheduledThreadPoolExecutor` — always `stop()` in `finally` to release non-daemon threads.

## 3. Test Doubles (shared, defined in-module)

Defined once under `simba-core/src/test/kotlin/me/ahoo/simba/core/`:

- **`FixedClockOwner`** — `MutexOwner` subclass with overrideable `currentAt`. (Already exists privately in `ContendPeriodTest`; promote to a top-level test helper so all tests reuse it.)
- **`FakeMutexContender`** — implements `MutexContender`; records `onAcquired`/`onReleased` calls into lists. Inherits the default `notifyOwner` dispatch (exercising its 4 branches).
- **`FakeMutexContendService`** — extends `AbstractMutexContendService`; configurable `ownerToNotify`, `throwOnStartRetrieval`, `throwOnStopRetrieval`; exposes the protected `notifyOwner` via a public test method; uses a synchronous executor by default.
- **`FakeContendServiceFactory`** — `MutexContendServiceFactory` returning a preconfigured `FakeMutexContendService` (used by `SimbaLocker` and `AbstractScheduler` tests).
- **`NoopContendServiceFactory`** — variant for `SimbaLocker` tests where we control `isOwner` directly.

## 4. Test File Plan (13 files)

All paths under `simba-core/src/test/kotlin/me/ahoo/simba/`.

### P0 — Critical state machine & value types (highest branch density)

#### 4.1 `core/AbstractMutexRetrievalServiceTest.kt`
Target: the lifecycle state machine — currently 0% direct coverage.

Branch coverage:
- `start()` CAS success: INITIAL → STARTING → (startRetrieval) → RUNNING. Assert status transitions and that `startRetrieval` was invoked.
- `start()` CAS failure: calling `start()` from RUNNING → `IllegalStateException("Cannot start from state [RUNNING]...")`. Also test STARTING/STOPPING states.
- `start()` try/catch rollback: `FakeMutexContendService.throwOnStartRetrieval = RuntimeException` → assert status resets to INITIAL **and** exception propagates.
- `stop()` CAS success: RUNNING → STOPPING → (stopRetrieval) → INITIAL (in `finally`). Assert `stopRetrieval` invoked.
- `stop()` CAS failure: `stop()` from INITIAL → `IllegalStateException("Cannot stop...from state:[INITIAL]...")`.
- `stop()` finally resets on throw: `throwOnStopRetrieval = RuntimeException` → assert status == INITIAL **and** exception propagates.
- `safeNotifyOwner` try/catch swallows: `FakeMutexContender` whose `notifyOwner` throws → `notifyOwner(...).join()` completes without exception; `mutexState` was still updated (assignment happens before the throw, per the "Order of assignment" comment).
- `safeNotifyOwner` happy path: newOwner applied; `retriever.notifyOwner(newState)` called with correct before/after.
- `close()` delegates to `stop()`: from INITIAL → `IllegalStateException`.
- `resetOwner()` sets `mutexState = MutexState.NONE` (exercised via `AbstractMutexContendService.startRetrieval` calling it).

Uses synchronous `Executor` and `FakeMutexContendService`/`FakeMutexContender`.

#### 4.2 `core/MutexStateTest.kt`
Branch coverage for each predicate (true/false combos):
- `isChanged`: `MutexState(A, A)` → false; `MutexState(A, B)` → true; `MutexState.NONE.isChanged` → false.
- `isAcquired(contenderId)`: changed+owner → true; changed+non-owner → false; unchanged+owner → false.
- `isReleased(contenderId)`: changed+before-owner → true; changed+not-before-owner → false; unchanged → false.
- `isOwner(contenderId)`: after == contenderId → true/false.
- `isInTtl(contenderId)`: owner+inTtl, owner+expired, non-owner.
- Data class contract: `equals`/`hashCode`/`copy`/`component1`/`component2`; `NONE == MutexState(MutexOwner.NONE, MutexOwner.NONE)`.

#### 4.3 `core/MutexOwnerTest.kt`
Branch coverage (using `FixedClockOwner`):
- `isOwner(contenderId)`: true/false.
- `isInTtl`: `ttlAt > currentAt` true; `ttlAt == currentAt` false (strict `>`); `ttlAt < currentAt` false.
- `isInTtl(contenderId)`: owner×inTtl, owner×expired, non-owner×inTtl (short-circuits false).
- `isInTransition`: `transitionAt >= currentAt` true at equality boundary; `transitionAt < currentAt` false.
- `isInTransitionOf(contenderId)`: 4 combos.
- `hasOwner()`: `transitionAt >= currentAt` boundary; `MutexOwner.NONE.hasOwner()` → false.
- Default args: `MutexOwner("x")` → `isInTtl == true`, `isInTransition == true`, `hasOwner() == true` (MAX_VALUE > now).
- Companion: `NONE_OWNER_ID == ""`; `NONE` is `MutexOwner("", 0, 0, 0)` with `isInTtl == false`, `isInTransition == false`, `hasOwner() == false`.

#### 4.4 `core/AbstractMutexContenderTest.kt`
Branch coverage:
- `require(mutex.isNotBlank())`: `""` and `"   "` (whitespace) → `IllegalArgumentException("mutex must not be blank!")`.
- `require(contenderId.isNotBlank())`: blank contenderId → `IllegalArgumentException("contenderId must not be blank!")`.
- Default `contenderId` arg: omit it → assert non-blank id matching `HostContenderIdGenerator` format `"\d+:\d+@.+"`.
- `onAcquired`/`onReleased` default impls: subclass and invoke; they exercise the `log.info {}` lambda (no exception).
- `final override val mutex`/`contenderId` are the constructor args.

#### 4.5 `core/ContendPeriodTest.kt` (extend existing)
Add to the existing file:
- `ensureNextDelay`: negative computed delay (owner with `ttlAt < currentAt`) → returns `0`; positive → returns computed value.
- `nextDelay`: owner branch (delegates to `nextOwnerDelay`) vs contender branch (delegates to `nextContenderDelay`).
- `nextContenderDelay` `transition == 0L` branch: `transitionAt == ttlAt` → min = 0; `transitionAt > ttlAt` → min = -200. Assert result falls in expected `[transitionAt - now + min, transitionAt - now + 1000)` range (ThreadLocalRandom is non-deterministic → range assertion).

### P1 — Contender dispatch, locker, scheduler (real branches)

#### 4.6 `core/MutexContenderTest.kt`
Tests the default `notifyOwner` interface dispatch (4 branches) via `FakeMutexContender`:
- `!mutexState.isChanged` → early return; neither callback fires (`MutexState(A, A)`).
- `isAcquired(contenderId)` → `onAcquired` called once (`MutexState(B, A)` where A is contender).
- `isReleased(contenderId)` → `onReleased` called once (`MutexState(A, B)` where A is contender).
- Combined role-swap `MutexState(A, B)` with contender A → `onReleased` for A; with contender B → `onAcquired` for B (assert per-contender only one fires).

#### 4.7 `locker/SimbaLockerTest.kt`
Branch coverage:
- `acquire(timeout)` CAS failure: double-acquire from same thread → `IllegalMonitorStateException("Thread[...] already owns this lock[...].")`.
- `acquire(timeout)` timeout: fake service with `isOwner == false` and short timeout → `TimeoutException("Could not acquire [$contenderId]@mutex:[$mutex] within timeout of ${...}ms")`.
- `acquire(timeout)` success: fake service flips `isOwner` to true before timeout → returns normally.
- `acquire()` CAS failure: same `IllegalMonitorStateException` (no timeout variant).
- `acquire()` happy path: acquire from a separate thread; fake service invokes `onAcquired` (which calls `LockSupport.unpark`) → calling thread unparks and returns. Use `CountDownLatch` to synchronize.
- `onAcquired`: calls `super.onAcquired` (logs) then `LockSupport.unpark(OWNER[this])`; when `OWNER[this]` is null (not acquired) → no-op, no exception.
- `close()`: delegates to `contendService.stop()`; calling `close()` without prior `acquire()` (status INITIAL) → `IllegalStateException`.

Uses `FakeContendServiceFactory` / `NoopContendServiceFactory` controlling `isOwner`.

#### 4.8 `schedule/AbstractSchedulerTest.kt`
Branch coverage:
- `WorkContender.onAcquired` guard `workFuture == null || isDone`: first acquire schedules; second acquire while scheduled does NOT reschedule (assert only one `ScheduledFuture` exists / `work()` invocation count respects single schedule).
- Strategy branch: `ScheduleConfig.rate(...)` → `scheduleAtFixedRate`; `ScheduleConfig.delay(...)` → `scheduleWithFixedDelay`. Assert `work()` is invoked in both modes (via `CountDownLatch`).
- `onReleased`: `workFuture?.cancel(true)` — cancel an active future; release before any acquire (future null) is a no-op.
- `safeWork` try/catch: `work()` throws → exception logged and swallowed, scheduled task continues (next period still runs). Assert via a second `work()` invocation after a thrown one.
- `start()`/`stop()`/`running`: delegate to `contendService`; `running` reflects `status.isActive`.

Uses a concrete `AbstractScheduler` subclass with a `CountDownLatch`-recording `work()` and `ScheduleConfig.rate`/`.delay`. Always `stop()` in `finally`.

### P2 — Constants, factories, utilities (low branch density but closes 0% files)

#### 4.9 `core/ContenderIdGeneratorTest.kt`
- `UUIDContenderIdGenerator.generate()`: 32 hex chars, matches `[0-9a-f]{32}`, two calls differ.
- `HostContenderIdGenerator.generate()`: matches `\d+:\d+@.+`, counter increments (first call counter=0, second=1).
- Companion `@JvmField`s: `ContenderIdGenerator.UUID === UUIDContenderIdGenerator`, `HOST === HostContenderIdGenerator`.

#### 4.10 `schedule/ScheduleConfigTest.kt`
- `rate(initialDelay, period).strategy == FIXED_RATE`.
- `delay(initialDelay, period).strategy == FIXED_DELAY`.
- Data class `equals`/`copy`/`componentN`.
- `enum class Strategy` has exactly `[FIXED_DELAY, FIXED_RATE]` in order.

#### 4.11 `util/ThreadsTest.kt`
- `Threads.defaultFactory("foo")`: `newThread(runnable)` → `isDaemon == false`, `name == "foo-0"` (first), `"foo-1"` (second).

#### 4.12 `core/MutexRetrievalServiceTest.kt`
- `Status.isActive`: INITIAL → false, STARTING → true, RUNNING → true, STOPPING → false.
- `hasOwner()` default: `afterOwner !== MutexOwner.NONE` → true when after is a real owner, false when after is `MutexOwner.NONE` (identity).
- (Interface defaults exercised via `FakeMutexContendService`.)

#### 4.13 `SimbaTest.kt` + `SimbaExceptionTest.kt`
- `SimbaTest`: `Simba.SIMBA == "simba"`, `Simba.SIMBA_PREFIX == "simba."`.
- `SimbaExceptionTest`: instantiate all 5 constructors; assert `message`, `cause`, `suppressed`, `stackTrace` fields; assert it IS-A `RuntimeException`; assert `open` allows subclassing.

## 5. Files Changed

**New (13 test files + 1 shared helper):**
- `simba-core/src/test/kotlin/me/ahoo/simba/core/TestDoubles.kt` (shared `FixedClockOwner`, `FakeMutexContender`, `FakeMutexContendService`, `FakeContendServiceFactory`, `NoopContendServiceFactory`)
- `simba-core/src/test/kotlin/me/ahoo/simba/core/AbstractMutexRetrievalServiceTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/core/MutexStateTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/core/MutexOwnerTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/core/AbstractMutexContenderTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/core/MutexContenderTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/core/ContenderIdGeneratorTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/core/MutexRetrievalServiceTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/locker/SimbaLockerTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/schedule/AbstractSchedulerTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/schedule/ScheduleConfigTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/util/ThreadsTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/SimbaTest.kt`
- `simba-core/src/test/kotlin/me/ahoo/simba/SimbaExceptionTest.kt`

**Modified (1):**
- `simba-core/src/test/kotlin/me/ahoo/simba/core/ContendPeriodTest.kt` — extend with `ensureNextDelay`/`nextDelay`/`nextContenderDelay` branch tests; replace private `FixedClockOwner` with import from `TestDoubles.kt`.

**No production code changes.** No `build.gradle.kts` changes (all test deps already on classpath via root `configure(libraryProjects)`).

## 6. Verification

```bash
./gradlew simba-core:test                 # all new tests pass
./gradlew simba-core:detekt               # style clean
./gradlew codeCoverageReport              # regenerate aggregated report
```

Expected outcome:
- `simba-core` line coverage → ~95%+ (from ~80%).
- `simba-core` branch coverage → significant improvement (state machine, value types, contender dispatch fully covered).
- No external services required; full run completes in seconds.

## 7. Out of Scope

- Other modules (`simba-jdbc`, `simba-spring-redis`, `simba-zookeeper`, `simba-spring-boot-starter`) — separate effort.
- Adding Gradle `jacocoTestCoverageVerification` thresholds — separate decision; can be a follow-up once the new baseline is established.
- Production code refactors — none. If a test reveals a genuine bug, stop and surface it rather than patching silently.

## 8. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| `SimbaLocker.acquire()` blocks the test thread | Use `acquire(timeout)` for direct tests; for happy path, run `acquire()` on a separate thread and unpark via `onAcquired` with `CountDownLatch` synchronization. Hard timeout on test thread join to prevent hangs. |
| `AbstractScheduler` uses non-daemon threads → JVM won't exit | Always `stop()` in `finally`; fail test if executor threads linger (assert via thread name lookup if needed). |
| `ThreadLocalRandom` in `ContendPeriod.nextContenderDelay` is non-deterministic | Assert result range, not exact value; run multiple iterations to exercise both `transition == 0` and `!= 0` branches reliably. |
| `HostContenderIdGenerator` counter is global state shared across tests | Assert relative increments (call N+1 > call N), not absolute values; tolerate any starting counter. |
| Promoting `FixedClockOwner` out of `ContendPeriodTest` breaks the existing test | Update `ContendPeriodTest` to import from `TestDoubles.kt` in the same change; verify it still passes. |
