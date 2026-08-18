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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class MutexOwnerTest {
    @Test
    fun `isOwner returns true when ids match`() {
        val owner = MutexOwner("A", 0, 100, 200)
        owner.isOwner("A").assert().isTrue()
    }

    @Test
    fun `isOwner returns false when ids differ`() {
        val owner = MutexOwner("A", 0, 100, 200)
        owner.isOwner("B").assert().isFalse()
    }

    @Test
    fun `isInTtl is true when ttlAt greater than currentAt`() {
        val owner = FixedClockOwner("A", ttlAt = 200, transitionAt = 300, fixedCurrentAt = 100)
        owner.isInTtl.assert().isTrue()
    }

    @Test
    fun `isInTtl is false when ttlAt equals currentAt`() {
        val owner = FixedClockOwner("A", ttlAt = 100, transitionAt = 300, fixedCurrentAt = 100)
        owner.isInTtl.assert().isFalse()
    }

    @Test
    fun `isInTtl is false when ttlAt less than currentAt`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 300, fixedCurrentAt = 100)
        owner.isInTtl.assert().isFalse()
    }

    @Test
    fun `isInTtl contenderId is true for owner in ttl`() {
        val owner = FixedClockOwner("A", ttlAt = 200, transitionAt = 300, fixedCurrentAt = 100)
        owner.isInTtl("A").assert().isTrue()
    }

    @Test
    fun `isInTtl contenderId is false for owner expired`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 300, fixedCurrentAt = 100)
        owner.isInTtl("A").assert().isFalse()
    }

    @Test
    fun `isInTtl contenderId is false for non owner even if in ttl`() {
        val owner = FixedClockOwner("A", ttlAt = 200, transitionAt = 300, fixedCurrentAt = 100)
        owner.isInTtl("B").assert().isFalse()
    }

    @Test
    fun `isInTransition is true at equality boundary`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 100, fixedCurrentAt = 100)
        owner.isInTransition.assert().isTrue()
    }

    @Test
    fun `isInTransition is false when transitionAt less than currentAt`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 50, fixedCurrentAt = 100)
        owner.isInTransition.assert().isFalse()
    }

    @Test
    fun `isInTransitionOf is true for owner in transition`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 200, fixedCurrentAt = 100)
        owner.isInTransitionOf("A").assert().isTrue()
    }

    @Test
    fun `isInTransitionOf is false for non owner`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 200, fixedCurrentAt = 100)
        owner.isInTransitionOf("B").assert().isFalse()
    }

    @Test
    fun `hasOwner is true at transition boundary`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 100, fixedCurrentAt = 100)
        owner.hasOwner().assert().isTrue()
    }

    @Test
    fun `hasOwner is false when transitionAt less than currentAt`() {
        val owner = FixedClockOwner("A", ttlAt = 50, transitionAt = 50, fixedCurrentAt = 100)
        owner.hasOwner().assert().isFalse()
    }

    @Test
    fun `default args yield inTtl and inTransition true`() {
        val owner = MutexOwner("A")
        owner.isInTtl.assert().isTrue()
        owner.isInTransition.assert().isTrue()
        owner.hasOwner().assert().isTrue()
    }

    @Test
    fun `NONE constant has empty ownerId and is expired`() {
        MutexOwner.NONE.ownerId.assert().isEqualTo(MutexOwner.NONE_OWNER_ID)
        MutexOwner.NONE_OWNER_ID.assert().isEmpty()
        MutexOwner.NONE.isInTtl.assert().isFalse()
        MutexOwner.NONE.isInTransition.assert().isFalse()
        MutexOwner.NONE.hasOwner().assert().isFalse()
    }
}
