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
