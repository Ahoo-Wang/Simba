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
