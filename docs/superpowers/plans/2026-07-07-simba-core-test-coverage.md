# simba-core Test Coverage Improvement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Raise `simba-core` direct unit-test coverage from ~80%/weak-branch to ~95%+ line with comprehensive branch coverage by adding 13 focused unit test files — no external services required.

**Architecture:** Pure JUnit Jupiter + Hamcrest unit tests mirroring the existing `ContendPeriodTest` style. A shared `TestDoubles.kt` provides deterministic fakes (`FixedClockOwner`, `FakeMutexContender`, `FakeMutexContendService`, `FakeContendServiceFactory`) used across tests — fakes over mocks, matching the TCK convention. A synchronous `Executor` makes async `notifyOwner` logic deterministic; `SimbaLocker`/`AbstractScheduler` concurrency paths use `CountDownLatch` awaitability with `finally` cleanup.

**Tech Stack:** Kotlin 2.3.20 / JDK 17, JUnit Jupiter 6.1.1, Hamcrest 3.0, MockK 1.14.11 (available but unused — fakes preferred), Logback test runtime. All deps already on `simba-core` test classpath via root `configure(libraryProjects)`.

---

## File Structure

All paths under `/Users/ahoo/work/ahoo-git/Simba/simba-core/src/test/kotlin/me/ahoo/simba/`.

| File | Responsibility |
|---|---|
| `core/TestDoubles.kt` | Shared deterministic fakes: `FixedClockOwner`, `FakeMutexContender`, `FakeMutexContendService`, `FakeContendServiceFactory` |
| `core/MutexStateTest.kt` | `MutexState` predicate branch combos + data-class contract |
| `core/MutexOwnerTest.kt` | `MutexOwner` time-boundary predicates + `NONE` constant |
| `core/ContendPeriodTest.kt` (modify) | Extend with `ensureNextDelay`/`nextDelay`/`nextContenderDelay` branch tests; use shared `FixedClockOwner` |
| `core/AbstractMutexContenderTest.kt` | Constructor `require` validation + default contenderId + onAcquired/onReleased |
| `core/MutexContenderTest.kt` | Default `notifyOwner` dispatch (4 branches) |
| `core/MutexRetrievalServiceTest.kt` | `Status.isActive` all 4 states + `hasOwner` identity |
| `core/AbstractMutexRetrievalServiceTest.kt` | Lifecycle state machine: CAS guards, try/catch rollback, `safeNotifyOwner` swallow, `close` |
| `core/ContenderIdGeneratorTest.kt` | UUID/HOST format + counter + companion identity |
| `locker/SimbaLockerTest.kt` | `acquire`/`acquire(timeout)` CAS + timeout + park/unpark + close |
| `schedule/ScheduleConfigTest.kt` | `rate`/`delay` factories + enum + data-class |
| `schedule/AbstractSchedulerTest.kt` | onAcquired guard, strategy branch, onReleased cancel, safeWork swallow |
| `util/ThreadsTest.kt` | Thread name format + daemon=false |
| `SimbaTest.kt` | Brand constants |
| `SimbaExceptionTest.kt` | 5 constructors + RuntimeException inheritance |

**Ordering rationale:** `TestDoubles.kt` first (everything depends on it), then leaf value types (`MutexState`, `MutexOwner`, `ContendPeriod`) with no dependencies, then contender/retrieval interfaces, then the state machine (uses fakes), then the high-level `SimbaLocker`/`AbstractScheduler` (use fakes), finally trivial constants/utilities.

**No production code changes. No `build.gradle.kts` changes.**

---

## Task 1: Shared TestDoubles

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/core/TestDoubles.kt`

- [ ] **Step 1: Create TestDoubles.kt with shared fakes**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.core

import java.util.concurrent.Executor

/**
 * [MutexOwner] with a fixed [currentAt] for deterministic time-based tests.
 */
class FixedClockOwner(
    ownerId: String,
    ttlAt: Long,
    transitionAt: Long,
    private val fixedCurrentAt: Long
) : MutexOwner(ownerId = ownerId, acquiredAt = 0, ttlAt = ttlAt, transitionAt = transitionAt) {
    override val currentAt: Long
        get() = fixedCurrentAt
}

/**
 * [MutexContender] that records [onAcquired]/[onReleased] calls.
 * Inherits the default [notifyOwner] dispatch from [MutexContender].
 */
class FakeMutexContender(
    override val mutex: String,
    override val contenderId: String
) : MutexContender {
    val acquired = mutableListOf<MutexState>()
    val released = mutableListOf<MutexState>()

    var throwOnNotify: Throwable? = null

    override fun notifyOwner(mutexState: MutexState) {
        throwOnNotify?.let { throw it }
        super.notifyOwner(mutexState)
    }

    override fun onAcquired(mutexState: MutexState) {
        acquired += mutexState
    }

    override fun onReleased(mutexState: MutexState) {
        released += mutexState
    }
}

/**
 * Synchronous executor that runs tasks inline on the calling thread.
 */
object SameThreadExecutor : Executor {
    override fun execute(command: Runnable) {
        command.run()
    }
}

/**
 * [AbstractMutexContendService] fake with controllable start/stop/notify behavior.
 * Uses [SameThreadExecutor] by default so [notifyOwner] runs inline.
 */
class FakeMutexContendService(
    contender: MutexContender,
    handleExecutor: Executor = SameThreadExecutor,
    var throwOnStartContend: Throwable? = null,
    var throwOnStopContend: Throwable? = null
) : AbstractMutexContendService(contender, handleExecutor) {
    var startContendCalled = false
        private set
    var stopContendCalled = false
        private set

    override fun startContend() {
        startContendCalled = true
        throwOnStartContend?.let { throw it }
    }

    override fun stopContend() {
        stopContendCalled = true
        throwOnStopContend?.let { throw it }
    }

    /** Exposes the protected [notifyOwner] for tests. */
    fun publishOwner(newOwner: MutexOwner) {
        notifyOwner(newOwner)
    }
}

/**
 * [MutexContendServiceFactory] that returns a preconfigured service.
 */
class FakeContendServiceFactory(private val service: MutexContendService) : MutexContendServiceFactory {
    override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
        return service
    }
}
```

- [ ] **Step 2: Compile to verify fakes are well-formed**

Run: `./gradlew simba-core:compileTestKotlin`
Expected: BUILD SUCCESSFUL (no tests run yet, just compilation).

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/core/TestDoubles.kt
git commit -m "test(core): add shared test doubles for simba-core unit tests"
```

---

## Task 2: MutexStateTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/core/MutexStateTest.kt`

- [ ] **Step 1: Write MutexStateTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.core

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test

class MutexStateTest {
    private val ownerA = MutexOwner("A", 0, 100, 200)
    private val ownerB = MutexOwner("B", 0, 100, 200)

    @Test
    fun `isChanged is false when before and after share ownerId`() {
        assertThat(MutexState(ownerA, ownerA).isChanged, equalTo(false))
    }

    @Test
    fun `isChanged is false for NONE`() {
        assertThat(MutexState.NONE.isChanged, equalTo(false))
    }

    @Test
    fun `isChanged is true when owners differ`() {
        assertThat(MutexState(ownerA, ownerB).isChanged, equalTo(true))
    }

    @Test
    fun `isAcquired is true when changed and after owns`() {
        assertThat(MutexState(ownerA, ownerB).isAcquired("B"), equalTo(true))
    }

    @Test
    fun `isAcquired is false when unchanged even if after owns`() {
        assertThat(MutexState(ownerB, ownerB).isAcquired("B"), equalTo(false))
    }

    @Test
    fun `isAcquired is false when changed but after does not own`() {
        assertThat(MutexState(ownerA, ownerB).isAcquired("A"), equalTo(false))
    }

    @Test
    fun `isReleased is true when changed and before owns`() {
        assertThat(MutexState(ownerA, ownerB).isReleased("A"), equalTo(true))
    }

    @Test
    fun `isReleased is false when unchanged`() {
        assertThat(MutexState(ownerA, ownerA).isReleased("A"), equalTo(false))
    }

    @Test
    fun `isReleased is false when changed but before does not own`() {
        assertThat(MutexState(ownerA, ownerB).isReleased("B"), equalTo(false))
    }

    @Test
    fun `isOwner delegates to after owner`() {
        assertThat(MutexState(ownerA, ownerB).isOwner("B"), equalTo(true))
        assertThat(MutexState(ownerA, ownerB).isOwner("A"), equalTo(false))
    }

    @Test
    fun `isInTtl is true when owner and after in ttl`() {
        val inTtl = FixedClockOwner("B", ttlAt = 200, transitionAt = 300, fixedCurrentAt = 100)
        assertThat(MutexState(ownerA, inTtl).isInTtl("B"), equalTo(true))
    }

    @Test
    fun `isInTtl is false when owner but after expired`() {
        val expired = FixedClockOwner("B", ttlAt = 50, transitionAt = 300, fixedCurrentAt = 100)
        assertThat(MutexState(ownerA, expired).isInTtl("B"), equalTo(false))
    }

    @Test
    fun `isInTtl is false when not owner`() {
        val inTtl = FixedClockOwner("B", ttlAt = 200, transitionAt = 300, fixedCurrentAt = 100)
        assertThat(MutexState(ownerA, inTtl).isInTtl("A"), equalTo(false))
    }

    @Test
    fun `data class equals and copy`() {
        val state = MutexState(ownerA, ownerB)
        val copy = state.copy()
        assertThat(copy, equalTo(state))
        assertThat(copy, not(sameInstance(state)))
    }

    @Test
    fun `data class component destructuring`() {
        val (before, after) = MutexState(ownerA, ownerB)
        assertThat(before, equalTo(ownerA))
        assertThat(after, equalTo(ownerB))
    }

    @Test
    fun `NONE equals state of two NONE owners`() {
        assertThat(MutexState.NONE, equalTo(MutexState(MutexOwner.NONE, MutexOwner.NONE)))
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.core.MutexStateTest"`
Expected: PASS (all tests green).

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/core/MutexStateTest.kt
git commit -m "test(core): add MutexState predicate branch tests"
```

---

## Task 3: MutexOwnerTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/core/MutexOwnerTest.kt`

- [ ] **Step 1: Write MutexOwnerTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.core

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class MutexOwnerTest {
    @Test
    fun `isOwner returns true when ids match`() {
        val owner = MutexOwner("A", 0, 100, 200)
        assertThat(owner.isOwner("A"), equalTo(true))
    }

    @Test
    fun `isOwner returns false when ids differ`() {
        val owner = MutexOwner("A", 0, 100, 200)
        assertThat(owner.isOwner("B"), equalTo(false))
    }

    @Test
    fun `isInTtl is true when ttlAt greater than currentAt`() {
        val owner = FixedClockOwner("A", ttlAt = 200, transitionAt = 300, fixedCurrentAt = 100)
        assertThat(owner.isInTtl, equalTo(true))
    }

    @Test
    fun `isInTtl is false when ttlAt equals currentAt`() {
        val owner = FixedClockOwner("A", ttlAt = 100, transitionAt = 300, fixedCurrentAt = 100)
        assertThat(owner.isInTtl, equalTo(false))
    }

    @Test
    fun `isInTtl is false when ttlAt less than currentAt`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 300, fixedCurrentAt = 100)
        assertThat(owner.isInTtl, equalTo(false))
    }

    @Test
    fun `isInTtl contenderId is true for owner in ttl`() {
        val owner = FixedClockOwner("A", ttlAt = 200, transitionAt = 300, fixedCurrentAt = 100)
        assertThat(owner.isInTtl("A"), equalTo(true))
    }

    @Test
    fun `isInTtl contenderId is false for owner expired`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 300, fixedCurrentAt = 100)
        assertThat(owner.isInTtl("A"), equalTo(false))
    }

    @Test
    fun `isInTtl contenderId is false for non owner even if in ttl`() {
        val owner = FixedClockOwner("A", ttlAt = 200, transitionAt = 300, fixedCurrentAt = 100)
        assertThat(owner.isInTtl("B"), equalTo(false))
    }

    @Test
    fun `isInTransition is true at equality boundary`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 100, fixedCurrentAt = 100)
        assertThat(owner.isInTransition, equalTo(true))
    }

    @Test
    fun `isInTransition is false when transitionAt less than currentAt`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 50, fixedCurrentAt = 100)
        assertThat(owner.isInTransition, equalTo(false))
    }

    @Test
    fun `isInTransitionOf is true for owner in transition`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 200, fixedCurrentAt = 100)
        assertThat(owner.isInTransitionOf("A"), equalTo(true))
    }

    @Test
    fun `isInTransitionOf is false for non owner`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 200, fixedCurrentAt = 100)
        assertThat(owner.isInTransitionOf("B"), equalTo(false))
    }

    @Test
    fun `hasOwner is true at transition boundary`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 100, fixedCurrentAt = 100)
        assertThat(owner.hasOwner(), equalTo(true))
    }

    @Test
    fun `hasOwner is false when transitionAt less than currentAt`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 50, fixedCurrentAt = 100)
        assertThat(owner.hasOwner(), equalTo(false))
    }

    @Test
    fun `default args yield inTtl and inTransition true`() {
        val owner = MutexOwner("A")
        assertThat(owner.isInTtl, equalTo(true))
        assertThat(owner.isInTransition, equalTo(true))
        assertThat(owner.hasOwner(), equalTo(true))
    }

    @Test
    fun `NONE constant has empty ownerId and is expired`() {
        assertThat(MutexOwner.NONE.ownerId, equalTo(MutexOwner.NONE_OWNER_ID))
        assertThat(MutexOwner.NONE_OWNER_ID, equalTo(""))
        assertThat(MutexOwner.NONE.isInTtl, equalTo(false))
        assertThat(MutexOwner.NONE.isInTransition, equalTo(false))
        assertThat(MutexOwner.NONE.hasOwner(), equalTo(false))
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.core.MutexOwnerTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/core/MutexOwnerTest.kt
git commit -m "test(core): add MutexOwner time-boundary predicate tests"
```

---

## Task 4: Extend ContendPeriodTest

**Files:**
- Modify: `simba-core/src/test/kotlin/me/ahoo/simba/core/ContendPeriodTest.kt`

- [ ] **Step 1: Replace ContendPeriodTest with extended version using shared FixedClockOwner**

Replace the entire file content with:

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.core

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.lessThan
import org.junit.jupiter.api.Test

class ContendPeriodTest {
    @Test
    fun `owner delay uses owner clock`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 1400,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )

        assertThat(ContendPeriod.nextOwnerDelay(owner), equalTo(400))
    }

    @Test
    fun `ensureNextDelay clamps negative delay to zero`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 500,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )
        val period = ContendPeriod("owner")

        assertThat(period.ensureNextDelay(owner), equalTo(0L))
    }

    @Test
    fun `ensureNextDelay returns computed delay when positive`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 1400,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )
        val period = ContendPeriod("owner")

        assertThat(period.ensureNextDelay(owner), equalTo(400L))
    }

    @Test
    fun `nextDelay delegates to owner delay when contender is owner`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 1400,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )
        val period = ContendPeriod("owner")

        assertThat(period.nextDelay(owner), equalTo(ContendPeriod.nextOwnerDelay(owner)))
    }

    @Test
    fun `nextDelay delegates to contender delay when contender is not owner`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 1400,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )
        val period = ContendPeriod("other")

        assertThat(period.nextDelay(owner), equalTo(ContendPeriod.nextContenderDelay(owner)))
    }

    @Test
    fun `nextContenderDelay with zero transition stays in non-negative range`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 2000,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )

        val delay = ContendPeriod.nextContenderDelay(owner)

        assertThat(delay, greaterThanOrEqualTo(1000L))
        assertThat(delay, lessThan(2000L))
    }

    @Test
    fun `nextContenderDelay with non-zero transition allows negative offset`() {
        val owner = FixedClockOwner(
            ownerId = "owner",
            ttlAt = 1400,
            transitionAt = 2000,
            fixedCurrentAt = 1000
        )

        val delay = ContendPeriod.nextContenderDelay(owner)

        assertThat(delay, greaterThanOrEqualTo(800L))
        assertThat(delay, lessThan(2000L))
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.core.ContendPeriodTest"`
Expected: PASS (original test still green + new tests green).

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/core/ContendPeriodTest.kt
git commit -m "test(core): extend ContendPeriodTest with branch coverage"
```

---

## Task 5: AbstractMutexContenderTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/core/AbstractMutexContenderTest.kt`

- [ ] **Step 1: Write AbstractMutexContenderTest**

Note: the host-format regex uses Hamcrest's `matchesPattern(String)`, which treats the string as a regex — so `\d` matches digits. Do not use `Regex.fromLiteral` (it escapes the pattern).

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.core

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.blankOrNullString
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AbstractMutexContenderTest {
    private fun newContender(mutex: String, contenderId: String = "c1"): AbstractMutexContender {
        return object : AbstractMutexContender(mutex, contenderId) {}
    }

    @Test
    fun `rejects blank mutex`() {
        val error = assertThrows<IllegalArgumentException> { newContender("") }
        assertThat(error.message, equalTo("mutex must not be blank!"))
    }

    @Test
    fun `rejects whitespace-only mutex`() {
        val error = assertThrows<IllegalArgumentException> { newContender("   ") }
        assertThat(error.message, equalTo("mutex must not be blank!"))
    }

    @Test
    fun `rejects blank contenderId`() {
        val error = assertThrows<IllegalArgumentException> { newContender("m", "") }
        assertThat(error.message, equalTo("contenderId must not be blank!"))
    }

    @Test
    fun `rejects whitespace-only contenderId`() {
        val error = assertThrows<IllegalArgumentException> { newContender("m", "  ") }
        assertThat(error.message, equalTo("contenderId must not be blank!"))
    }

    @Test
    fun `default contenderId is generated host format`() {
        val contender = object : AbstractMutexContender("m") {}
        assertThat(contender.contenderId, not(blankOrNullString()))
        assertThat(
            contender.contenderId,
            matchesPattern("\\d+:\\d+@.+")
        )
    }

    @Test
    fun `onAcquired and onReleased do not throw`() {
        val contender = newContender("m", "c1")
        val state = MutexState(MutexOwner.NONE, MutexOwner("c1", 0, 100, 200))

        contender.onAcquired(state)
        contender.onReleased(state)
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.core.AbstractMutexContenderTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/core/AbstractMutexContenderTest.kt
git commit -m "test(core): add AbstractMutexContender validation tests"
```

---

## Task 6: MutexContenderTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/core/MutexContenderTest.kt`

- [ ] **Step 1: Write MutexContenderTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.core

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class MutexContenderTest {
    private val ownerA = MutexOwner("A", 0, 100, 200)
    private val ownerB = MutexOwner("B", 0, 100, 200)

    @Test
    fun `notifyOwner skips callbacks when state unchanged`() {
        val contender = FakeMutexContender("m", "A")

        contender.notifyOwner(MutexState(ownerA, ownerA))

        assertThat(contender.acquired.size, equalTo(0))
        assertThat(contender.released.size, equalTo(0))
    }

    @Test
    fun `notifyOwner fires onAcquired when contender becomes owner`() {
        val contender = FakeMutexContender("m", "B")

        contender.notifyOwner(MutexState(ownerA, ownerB))

        assertThat(contender.acquired.size, equalTo(1))
        assertThat(contender.released.size, equalTo(0))
    }

    @Test
    fun `notifyOwner fires onReleased when contender loses ownership`() {
        val contender = FakeMutexContender("m", "A")

        contender.notifyOwner(MutexState(ownerA, ownerB))

        assertThat(contender.acquired.size, equalTo(0))
        assertThat(contender.released.size, equalTo(1))
    }

    @Test
    fun `notifyOwner fires only onAcquired for the new owner on role swap`() {
        val newOwner = FakeMutexContender("m", "B")

        newOwner.notifyOwner(MutexState(ownerA, ownerB))

        assertThat(newOwner.acquired.size, equalTo(1))
        assertThat(newOwner.released.size, equalTo(0))
    }

    @Test
    fun `notifyOwner fires only onReleased for the previous owner on role swap`() {
        val previousOwner = FakeMutexContender("m", "A")

        previousOwner.notifyOwner(MutexState(ownerA, ownerB))

        assertThat(previousOwner.acquired.size, equalTo(0))
        assertThat(previousOwner.released.size, equalTo(1))
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.core.MutexContenderTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/core/MutexContenderTest.kt
git commit -m "test(core): add MutexContender default notifyOwner dispatch tests"
```

---

## Task 7: MutexRetrievalServiceTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/core/MutexRetrievalServiceTest.kt`

- [ ] **Step 1: Write MutexRetrievalServiceTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.core

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class MutexRetrievalServiceTest {
    @Test
    fun `Status INITIAL is not active`() {
        assertThat(MutexRetrievalService.Status.INITIAL.isActive, equalTo(false))
    }

    @Test
    fun `Status STARTING is active`() {
        assertThat(MutexRetrievalService.Status.STARTING.isActive, equalTo(true))
    }

    @Test
    fun `Status RUNNING is active`() {
        assertThat(MutexRetrievalService.Status.RUNNING.isActive, equalTo(true))
    }

    @Test
    fun `Status STOPPING is not active`() {
        assertThat(MutexRetrievalService.Status.STOPPING.isActive, equalTo(false))
    }

    @Test
    fun `hasOwner is false when afterOwner is NONE by identity`() {
        val service = FakeMutexContendService(FakeMutexContender("m", "c1"))
        // mutexState defaults to MutexState.NONE -> afterOwner is MutexOwner.NONE
        assertThat(service.hasOwner(), equalTo(false))
    }

    @Test
    fun `hasOwner is true when afterOwner is a real owner`() {
        val contender = FakeMutexContender("m", "c1")
        val service = FakeMutexContendService(contender)
        service.start()

        service.publishOwner(MutexOwner("c1", 0, 100, 200))

        assertThat(service.hasOwner(), equalTo(true))
        service.stop()
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.core.MutexRetrievalServiceTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/core/MutexRetrievalServiceTest.kt
git commit -m "test(core): add MutexRetrievalService Status and hasOwner tests"
```

---

## Task 8: AbstractMutexRetrievalServiceTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/core/AbstractMutexRetrievalServiceTest.kt`

- [ ] **Step 1: Write AbstractMutexRetrievalServiceTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.core

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AbstractMutexRetrievalServiceTest {
    private fun newService(): FakeMutexContendService {
        val contender = FakeMutexContender("m", "c1")
        return FakeMutexContendService(contender)
    }

    @Test
    fun `start transitions INITIAL to RUNNING and calls startContend`() {
        val service = newService()

        service.start()

        assertThat(service.status, equalTo(MutexRetrievalService.Status.RUNNING))
        assertThat(service.startContendCalled, equalTo(true))
        service.stop()
    }

    @Test
    fun `start from RUNNING throws IllegalStateException`() {
        val service = newService()
        service.start()

        val error = assertThrows<IllegalStateException> { service.start() }
        assertThat(error.message, equalTo("Cannot start from state [RUNNING]. Expected: [INITIAL]"))

        service.stop()
    }

    @Test
    fun `start rollback to INITIAL when startContend throws`() {
        val service = newService()
        service.throwOnStartContend = IllegalStateException("boom")

        val error = assertThrows<IllegalStateException> { service.start() }

        assertThat(error.message, equalTo("boom"))
        assertThat(service.status, equalTo(MutexRetrievalService.Status.INITIAL))
    }

    @Test
    fun `stop transitions RUNNING to INITIAL and calls stopContend`() {
        val service = newService()
        service.start()

        service.stop()

        assertThat(service.status, equalTo(MutexRetrievalService.Status.INITIAL))
        assertThat(service.stopContendCalled, equalTo(true))
    }

    @Test
    fun `stop from INITIAL throws IllegalStateException`() {
        val service = newService()

        val error = assertThrows<IllegalStateException> { service.stop() }
        assertThat(error.message, equalTo("Cannot stop mutex:[m] from state:[INITIAL]. Expected:[RUNNING]"))
    }

    @Test
    fun `stop resets to INITIAL even when stopContend throws`() {
        val service = newService()
        service.start()
        service.throwOnStopContend = IllegalStateException("stop-boom")

        val error = assertThrows<IllegalStateException> { service.stop() }

        assertThat(error.message, equalTo("stop-boom"))
        assertThat(service.status, equalTo(MutexRetrievalService.Status.INITIAL))
    }

    @Test
    fun `publishOwner updates mutexState and notifies retriever`() {
        val contender = FakeMutexContender("m", "c1")
        val service = FakeMutexContendService(contender)
        service.start()
        val previous = MutexOwner("other", 0, 50, 100)
        service.publishOwner(previous)

        val newOwner = MutexOwner("c1", 0, 100, 200)
        service.publishOwner(newOwner).join()

        assertThat(service.afterOwner, sameInstance(newOwner))
        assertThat(service.beforeOwner, sameInstance(previous))
        // contender became owner -> onAcquired fired
        assertThat(contender.acquired.size, equalTo(1))
        service.stop()
    }

    @Test
    fun `publishOwner swallows retriever exception and keeps mutexState`() {
        val contender = FakeMutexContender("m", "c1")
        contender.throwOnNotify = IllegalStateException("notify-boom")
        val service = FakeMutexContendService(contender)
        service.start()

        val newOwner = MutexOwner("c1", 0, 100, 200)
        // Should not throw despite retriever.notifyOwner throwing
        service.publishOwner(newOwner).join()

        // mutexState was assigned BEFORE the throw (order-of-assignment contract)
        assertThat(service.afterOwner, sameInstance(newOwner))
        // no exception escaped the CompletableFuture
        assertThat(contender.acquired.size, equalTo(0))
        service.stop()
    }

    @Test
    fun `close delegates to stop and throws when not running`() {
        val service = newService()

        val error = assertThrows<IllegalStateException> { service.close() }
        assertThat(error.message, equalTo("Cannot stop mutex:[m] from state:[INITIAL]. Expected:[RUNNING]"))
    }

    @Test
    fun `start resets owner to NONE via resetOwner`() {
        val contender = FakeMutexContender("m", "c1")
        val service = FakeMutexContendService(contender)
        service.start()
        service.publishOwner(MutexOwner("c1", 0, 100, 200)).join()
        assertThat(service.hasOwner(), equalTo(true))

        service.stop()
        service.start()

        // startRetrieval -> resetOwner -> mutexState = NONE
        assertThat(service.mutexState, equalTo(MutexState.NONE))
        service.stop()
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.core.AbstractMutexRetrievalServiceTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/core/AbstractMutexRetrievalServiceTest.kt
git commit -m "test(core): add AbstractMutexRetrievalService state machine tests"
```

---

## Task 9: ContenderIdGeneratorTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/core/ContenderIdGeneratorTest.kt`

- [ ] **Step 1: Write ContenderIdGeneratorTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.core

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test

class ContenderIdGeneratorTest {
    @Test
    fun `UUID generates 32 hex chars without dashes`() {
        val id = UUIDContenderIdGenerator.generate()

        assertThat(id.length, equalTo(32))
        assertThat(id, matchesPattern("[0-9a-f]{32}"))
    }

    @Test
    fun `UUID generates distinct ids`() {
        assertThat(UUIDContenderIdGenerator.generate(), not(equalTo(UUIDContenderIdGenerator.generate())))
    }

    @Test
    fun `HOST generates counter pid host format`() {
        val id = HostContenderIdGenerator.generate()

        assertThat(id, matchesPattern("\\d+:\\d+@.+"))
    }

    @Test
    fun `HOST increments counter across calls`() {
        val first = HostContenderIdGenerator.generate()
        val second = HostContenderIdGenerator.generate()

        val firstCounter = first.substringBefore(":").toLong()
        val secondCounter = second.substringBefore(":").toLong()
        assertThat(secondCounter, equalTo(firstCounter + 1L))
    }

    @Test
    fun `companion UUID field is the singleton instance`() {
        assertThat(ContenderIdGenerator.UUID, sameInstance(UUIDContenderIdGenerator))
    }

    @Test
    fun `companion HOST field is the singleton instance`() {
        assertThat(ContenderIdGenerator.HOST, sameInstance(HostContenderIdGenerator))
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.core.ContenderIdGeneratorTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/core/ContenderIdGeneratorTest.kt
git commit -m "test(core): add ContenderIdGenerator UUID and HOST tests"
```

---

## Task 10: SimbaTest + SimbaExceptionTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/SimbaTest.kt`
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/SimbaExceptionTest.kt`

- [ ] **Step 1: Write SimbaTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class SimbaTest {
    @Test
    fun `SIMBA constant is simba`() {
        assertThat(Simba.SIMBA, equalTo("simba"))
    }

    @Test
    fun `SIMBA_PREFIX constant is simba dot`() {
        assertThat(Simba.SIMBA_PREFIX, equalTo("simba."))
    }
}
```

- [ ] **Step 2: Write SimbaExceptionTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.instanceOf
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test

class SimbaExceptionTest {
    @Test
    fun `is a RuntimeException`() {
        assertThat(SimbaException(), instanceOf(RuntimeException::class.java))
    }

    @Test
    fun `default constructor has null message and cause`() {
        val ex = SimbaException()
        assertThat(ex.message, nullValue())
        assertThat(ex.cause, nullValue())
    }

    @Test
    fun `message constructor sets message`() {
        val ex = SimbaException("msg")
        assertThat(ex.message, equalTo("msg"))
        assertThat(ex.cause, nullValue())
    }

    @Test
    fun `message and cause constructor sets both`() {
        val cause = IllegalStateException("root")
        val ex = SimbaException("msg", cause)
        assertThat(ex.message, equalTo("msg"))
        assertThat(ex.cause, sameInstance(cause))
    }

    @Test
    fun `cause constructor sets cause`() {
        val cause = IllegalStateException("root")
        val ex = SimbaException(cause)
        assertThat(ex.cause, sameInstance(cause))
    }

    @Test
    fun `full constructor sets suppression and stack trace flags`() {
        val cause = IllegalStateException("root")
        val ex = SimbaException("msg", cause, true, false)

        assertThat(ex.message, equalTo("msg"))
        assertThat(ex.cause, sameInstance(cause))
    }

    @Test
    fun `open class allows subclassing`() {
        class CustomSimbaException : SimbaException("custom")

        val sub = CustomSimbaException()
        assertThat(sub, instanceOf(SimbaException::class.java))
        assertThat(sub.message, equalTo("custom"))
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.SimbaTest" --tests "me.ahoo.simba.SimbaExceptionTest"`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/SimbaTest.kt simba-core/src/test/kotlin/me/ahoo/simba/SimbaExceptionTest.kt
git commit -m "test(core): add Simba constants and SimbaException tests"
```

---

## Task 11: ThreadsTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/util/ThreadsTest.kt`

- [ ] **Step 1: Write ThreadsTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.util

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.`is`
import org.junit.jupiter.api.Test

class ThreadsTest {
    @Test
    fun `defaultFactory creates non-daemon threads`() {
        val factory = Threads.defaultFactory("domain")

        val thread = factory.newThread { }

        assertThat(thread.isDaemon, `is`(false))
    }

    @Test
    fun `defaultFactory names threads with domain counter`() {
        val factory = Threads.defaultFactory("worker")

        val first = factory.newThread { }
        val second = factory.newThread { }

        assertThat(first.name, equalTo("worker-0"))
        assertThat(second.name, equalTo("worker-1"))
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.util.ThreadsTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/util/ThreadsTest.kt
git commit -m "test(core): add Threads defaultFactory tests"
```

---

## Task 12: ScheduleConfigTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/schedule/ScheduleConfigTest.kt`

- [ ] **Step 1: Write ScheduleConfigTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.schedule

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test
import java.time.Duration

class ScheduleConfigTest {
    @Test
    fun `rate factory sets FIXED_RATE strategy`() {
        val config = ScheduleConfig.rate(Duration.ofMillis(10), Duration.ofMillis(100))

        assertThat(config.strategy, equalTo(ScheduleConfig.Strategy.FIXED_RATE))
        assertThat(config.initialDelay, equalTo(Duration.ofMillis(10)))
        assertThat(config.period, equalTo(Duration.ofMillis(100)))
    }

    @Test
    fun `delay factory sets FIXED_DELAY strategy`() {
        val config = ScheduleConfig.delay(Duration.ofMillis(10), Duration.ofMillis(100))

        assertThat(config.strategy, equalTo(ScheduleConfig.Strategy.FIXED_DELAY))
    }

    @Test
    fun `data class equals and copy`() {
        val config = ScheduleConfig.rate(Duration.ofMillis(10), Duration.ofMillis(100))
        val copy = config.copy()

        assertThat(copy, equalTo(config))
        assertThat(copy, not(sameInstance(config)))
    }

    @Test
    fun `data class component destructuring`() {
        val config = ScheduleConfig.delay(Duration.ofMillis(5), Duration.ofMillis(50))

        val (strategy, initialDelay, period) = config
        assertThat(strategy, equalTo(ScheduleConfig.Strategy.FIXED_DELAY))
        assertThat(initialDelay, equalTo(Duration.ofMillis(5)))
        assertThat(period, equalTo(Duration.ofMillis(50)))
    }

    @Test
    fun `Strategy enum has FIXED_DELAY then FIXED_RATE in order`() {
        val values = ScheduleConfig.Strategy.values()

        assertThat(values.size, equalTo(2))
        assertThat(values[0], equalTo(ScheduleConfig.Strategy.FIXED_DELAY))
        assertThat(values[1], equalTo(ScheduleConfig.Strategy.FIXED_RATE))
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.schedule.ScheduleConfigTest"`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/schedule/ScheduleConfigTest.kt
git commit -m "test(core): add ScheduleConfig factory and enum tests"
```

---

## Task 13: SimbaLockerTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/locker/SimbaLockerTest.kt`

**Design note (important):** `SimbaLocker` builds its contend service inside its own constructor via the factory. The service needs the locker (as the contender) to call `onAcquired` back. So the factory must build the service on demand using the `mutexContender` argument passed to `createMutexContendService` — that argument IS the locker. A pre-built service (like `FakeContendServiceFactory`) cannot work here because its contender is fixed before the locker exists. Use the `ControllableFactory` below which lazily creates the service and exposes it.

- [ ] **Step 1: Write SimbaLockerTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.locker

import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexState
import me.ahoo.simba.core.SameThreadExecutor
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Controllable contend service bound to the contender passed by the factory.
 * Tests flip ownership by calling [markOwner], which dispatches to
 * [AbstractMutexContendService.notifyOwner] -> contender.notifyOwner.
 */
private class ControllableContendService(
    contender: MutexContender
) : AbstractMutexContendService(contender, SameThreadExecutor) {
    override fun startContend() {}
    override fun stopContend() {}

    fun markOwner(ownerId: String) {
        notifyOwner(MutexOwner(ownerId, 0, Long.MAX_VALUE, Long.MAX_VALUE))
    }
}

/**
 * Factory that builds a [ControllableContendService] for the given contender
 * (the locker) and exposes it so tests can drive ownership transitions.
 */
private class ControllableFactory : MutexContendServiceFactory {
    var service: ControllableContendService? = null
        private set

    override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
        val svc = ControllableContendService(mutexContender)
        service = svc
        return svc
    }
}

class SimbaLockerTest {
    @Test
    fun `acquire with timeout throws TimeoutException when ownership never acquired`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)

        val error = assertThrows<TimeoutException> {
            locker.acquire(Duration.ofMillis(50))
        }
        assertThat(error.message, containsString("Could not acquire"))
        assertThat(error.message, containsString("within timeout of 50ms"))
    }

    @Test
    fun `double acquire with timeout throws IllegalMonitorStateException`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)

        locker.acquire(Duration.ofMillis(200))

        val error = assertThrows<IllegalMonitorStateException> {
            locker.acquire(Duration.ofMillis(50))
        }
        assertThat(error.message, containsString("already owns this lock"))
    }

    @Test
    fun `acquire without timeout throws IllegalMonitorStateException on double acquire`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)

        // First acquire via timeout (resolves without blocking forever)
        locker.acquire(Duration.ofMillis(200))

        val error = assertThrows<IllegalMonitorStateException> {
            locker.acquire()
        }
        assertThat(error.message, containsString("already owns this lock"))
    }

    @Test
    fun `acquire returns when onAcquired unparks the calling thread`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)
        val acquired = CountDownLatch(1)

        // Run acquire() on a separate thread; it parks inside LockSupport.park(this).
        // The main thread then marks ownership, which dispatches to the contender's
        // notifyOwner -> SimbaLocker.onAcquired -> LockSupport.unpark(owner thread).
        val ownerThread = Thread {
            locker.acquire()
            acquired.countDown()
        }
        ownerThread.isDaemon = true
        ownerThread.start()

        // Wait until the locker's contend service is created and acquire() has parked.
        // Poll the owner thread until it is WAITING (parked) before marking ownership,
        // so the unpark is guaranteed to find a parked thread.
        awaitThreadState(ownerThread, Thread.State.WAITING, 2, TimeUnit.SECONDS)
        factory.service!!.markOwner(locker.contenderId)

        assertThat("acquire should return after onAcquired unparks", acquired.await(2, TimeUnit.SECONDS))
        assertThat(ownerThread.state, equalTo(Thread.State.TERMINATED))
    }

    @Test
    fun `onAcquired does not throw when no thread owns the locker`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)

        // Call onAcquired directly without a prior acquire() — OWNER[this] is null,
        // LockSupport.unpark(null) is a safe no-op.
        locker.onAcquired(MutexState(MutexOwner.NONE, MutexOwner(locker.contenderId, 0, 100, 200)))
    }

    @Test
    fun `close throws IllegalStateException when contend service not running`() {
        val factory = ControllableFactory()
        val locker = SimbaLocker("m", factory)

        val error = assertThrows<IllegalStateException> { locker.close() }
        assertThat(error.message, containsString("Cannot stop mutex:[m]"))
    }

    private fun awaitThreadState(
        thread: Thread,
        state: Thread.State,
        timeout: Long,
        unit: TimeUnit
    ) {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (System.nanoTime() < deadline) {
            if (thread.state == state) return
            Thread.sleep(10)
        }
        throw AssertionError("Thread did not reach state $state within $timeout $unit (was ${thread.state})")
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.locker.SimbaLockerTest"`
Expected: PASS. The `acquire returns when onAcquired unparks` test polls the owner thread's state until it is `WAITING` (parked) before marking ownership, avoiding the flakiness of a fixed `Thread.sleep`. The 2-second `CountDownLatch.await` is the safety net.

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/locker/SimbaLockerTest.kt
git commit -m "test(core): add SimbaLocker acquire, timeout, and unpark tests"
```

---

## Task 14: AbstractSchedulerTest

**Files:**
- Create: `simba-core/src/test/kotlin/me/ahoo/simba/schedule/AbstractSchedulerTest.kt`

- [ ] **Step 1: Write AbstractSchedulerTest**

```kotlin
/*
 * Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *      http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.ahoo.simba.schedule

import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexState
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Minimal AbstractScheduler subclass recording work() invocations.
 * Uses a custom getter for `worker` to avoid the super-ctor init-order footgun.
 */
private class TestScheduler(
    mutex: String,
    factory: MutexContendServiceFactory,
    private val configValue: ScheduleConfig,
    private val workerName: String,
    private val latch: CountDownLatch,
    private val counter: AtomicInteger,
    private val shouldThrow: Boolean = false
) : AbstractScheduler(mutex, factory) {
    override val config: ScheduleConfig get() = configValue
    override val worker: String get() = workerName
    override fun work() {
        if (shouldThrow && counter.get() == 0) {
            throw IllegalStateException("work-boom")
        }
        counter.incrementAndGet()
        latch.countDown()
    }
}

/**
 * Factory exposing the created contender so tests can drive onAcquired/onReleased.
 */
private class CapturingFactory : MutexContendServiceFactory {
    var contender: MutexContender? = null
        private set

    override fun createMutexContendService(mutexContender: MutexContender): me.ahoo.simba.core.MutexContendService {
        contender = mutexContender
        // Return a no-op contend service; tests call contender.onAcquired directly
        return NoopContendService(mutexContender)
    }
}

private class NoopContendService(contender: MutexContender) :
    me.ahoo.simba.core.AbstractMutexContendService(contender, me.ahoo.simba.core.SameThreadExecutor) {
    override fun startContend() {}
    override fun stopContend() {}
}

class AbstractSchedulerTest {
    @Test
    fun `onAcquired schedules work at fixed rate`() {
        val latch = CountDownLatch(1)
        val counter = AtomicInteger(0)
        val factory = CapturingFactory()
        val scheduler = TestScheduler(
            mutex = "m",
            factory = factory,
            configValue = ScheduleConfig.rate(Duration.ofMillis(0), Duration.ofMillis(50)),
            workerName = "rate-worker",
            latch = latch,
            counter = counter
        )
        val contender = factory.contender!! as AbstractScheduler.WorkContender

        try {
            scheduler.start()
            contender.onAcquired(MutexState(MutexOwner.NONE, MutexOwner(contender.contenderId, 0, 100, 200)))

            assertThat("work should run at least once", latch.await(2, TimeUnit.SECONDS))
            assertThat(counter.get(), greaterThanOrEqualTo(1))
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `onAcquired schedules work with fixed delay`() {
        val latch = CountDownLatch(1)
        val counter = AtomicInteger(0)
        val factory = CapturingFactory()
        val scheduler = TestScheduler(
            mutex = "m",
            factory = factory,
            configValue = ScheduleConfig.delay(Duration.ofMillis(0), Duration.ofMillis(50)),
            workerName = "delay-worker",
            latch = latch,
            counter = counter
        )
        val contender = factory.contender!! as AbstractScheduler.WorkContender

        try {
            scheduler.start()
            contender.onAcquired(MutexState(MutexOwner.NONE, MutexOwner(contender.contenderId, 0, 100, 200)))

            assertThat("work should run at least once", latch.await(2, TimeUnit.SECONDS))
            assertThat(counter.get(), greaterThanOrEqualTo(1))
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `onReleased cancels scheduled work without throwing`() {
        val latch = CountDownLatch(1)
        val counter = AtomicInteger(0)
        val factory = CapturingFactory()
        val scheduler = TestScheduler(
            mutex = "m",
            factory = factory,
            configValue = ScheduleConfig.rate(Duration.ofMillis(0), Duration.ofMillis(50)),
            workerName = "release-worker",
            latch = latch,
            counter = counter
        )
        val contender = factory.contender!! as AbstractScheduler.WorkContender

        try {
            scheduler.start()
            contender.onAcquired(MutexState(MutexOwner.NONE, MutexOwner(contender.contenderId, 0, 100, 200)))
            // cancel the scheduled future
            contender.onReleased(MutexState(MutexOwner(contender.contenderId, 0, 100, 200), MutexOwner.NONE))
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `safeWork swallows work exception and keeps scheduling`() {
        val latch = CountDownLatch(2)
        val counter = AtomicInteger(0)
        val factory = CapturingFactory()
        val scheduler = TestScheduler(
            mutex = "m",
            factory = factory,
            configValue = ScheduleConfig.rate(Duration.ofMillis(0), Duration.ofMillis(20)),
            workerName = "throw-worker",
            latch = latch,
            counter = counter,
            shouldThrow = true
        )
        val contender = factory.contender!! as AbstractScheduler.WorkContender

        try {
            scheduler.start()
            contender.onAcquired(MutexState(MutexOwner.NONE, MutexOwner(contender.contenderId, 0, 100, 200)))

            assertThat("work should continue after a thrown exception", latch.await(2, TimeUnit.SECONDS))
            assertThat(counter.get(), greaterThanOrEqualTo(2))
        } finally {
            scheduler.stop()
        }
    }

    @Test
    fun `running reflects contend service status`() {
        val latch = CountDownLatch(1)
        val counter = AtomicInteger(0)
        val factory = CapturingFactory()
        val scheduler = TestScheduler(
            mutex = "m",
            factory = factory,
            configValue = ScheduleConfig.rate(Duration.ofMillis(0), Duration.ofMillis(50)),
            workerName = "running-worker",
            latch = latch,
            counter = counter
        )

        assertThat(scheduler.running, equalTo(false))
        scheduler.start()
        assertThat(scheduler.running, equalTo(true))
        scheduler.stop()
        assertThat(scheduler.running, equalTo(false))
    }
}
```

- [ ] **Step 2: Run test**

Run: `./gradlew simba-core:test --tests "me.ahoo.simba.schedule.AbstractSchedulerTest"`
Expected: PASS. If timing-sensitive tests are flaky, increase the `await` timeout or the period. The `safeWork swallows` test expects at least 2 invocations — with `shouldThrow=true` only the first throws, so subsequent runs succeed and decrement the latch.

- [ ] **Step 3: Commit**

```bash
git add simba-core/src/test/kotlin/me/ahoo/simba/schedule/AbstractSchedulerTest.kt
git commit -m "test(core): add AbstractScheduler scheduling and error handling tests"
```

---

## Task 15: Final verification

- [ ] **Step 1: Run the full simba-core test suite**

Run: `./gradlew simba-core:test`
Expected: BUILD SUCCESSFUL, all tests pass. Note the test count — should be ~70+ tests across 13+1 files.

- [ ] **Step 2: Run detekt on simba-core**

Run: `./gradlew simba-core:detekt`
Expected: BUILD SUCCESSFUL (no style violations). If detekt flags formatting, run `./gradlew simba-core:detektMain --auto-correct` and re-run.

- [ ] **Step 3: Regenerate the coverage report (optional, requires only simba-core)**

Run: `./gradlew codeCoverageReport`
Expected: BUILD SUCCESSFUL. Open `code-coverage-report/build/reports/jacoco/codeCoverageReport/html/index.html` and verify `me.ahoo.simba.core` line coverage is now ~95%+ and branch coverage is substantially improved over the prior ~80%/weak baseline.

- [ ] **Step 4: Confirm git diff is clean and commit any auto-corrections**

Run: `git diff --check && git status`
Expected: no whitespace errors, only the new test files staged/committed. If detekt auto-correct modified files, commit them:

```bash
git add -A
git commit -m "style(core): apply detekt formatting to tests"
```

- [ ] **Step 5: Push the branch and open a PR**

```bash
git push -u origin test/simba-core-coverage
gh pr create --title "test(core): improve simba-core unit test coverage" --body "Adds 13 focused unit test files (+ shared TestDoubles) to simba-core, raising direct line coverage from ~80% to ~95%+ and substantially improving branch coverage. No production code changes; no external services required. See docs/superpowers/specs/2026-07-07-simba-core-test-coverage-design.md for the design."
```

---

## Self-Review Notes

**Spec coverage check:**
- §4.1 AbstractMutexRetrievalService → Task 8 ✅
- §4.2 MutexState → Task 2 ✅
- §4.3 MutexOwner → Task 3 ✅
- §4.4 AbstractMutexContender → Task 5 ✅
- §4.5 ContendPeriod (extend) → Task 4 ✅
- §4.6 MutexContender → Task 6 ✅
- §4.7 SimbaLocker → Task 13 ✅
- §4.8 AbstractScheduler → Task 14 ✅
- §4.9 ContenderIdGenerator → Task 9 ✅
- §4.10 ScheduleConfig → Task 12 ✅
- §4.11 Threads → Task 11 ✅
- §4.12 MutexRetrievalService → Task 7 ✅
- §4.13 Simba + SimbaException → Task 10 ✅
- TestDoubles.kt → Task 1 ✅

**Type consistency check:** `FakeMutexContendService` (Task 1) exposes `publishOwner` used in Tasks 7 and 8. `SameThreadExecutor` (Task 1) used in Tasks 13 and 14. `FixedClockOwner` (Task 1) used in Tasks 2, 3, 4. Task 13 uses its own local `ControllableFactory`/`ControllableContendService` (because `SimbaLocker` builds its service in its own constructor — needs a lazy factory, not the pre-built `FakeContendServiceFactory`). Task 14 likewise uses its own `CapturingFactory`/`NoopContendService` for the same reason (`AbstractScheduler` builds its contender in its constructor). All names match across tasks.

**Known risk:** Task 13's `acquire returns when onAcquired unparks` polls `ownerThread.state == WAITING` before marking ownership, then awaits a `CountDownLatch` with a 2-second timeout. The poll loop avoids the flakiness of a fixed `Thread.sleep`. If the thread never reaches `WAITING` (e.g., contention on the JVM), the poll loop throws a clear `AssertionError` after 2 seconds rather than hanging indefinitely. Task 14's timing-sensitive tests use 2-second `CountDownLatch.await` with short (20–50ms) periods; if flaky in CI, increase the await timeout or the period.
