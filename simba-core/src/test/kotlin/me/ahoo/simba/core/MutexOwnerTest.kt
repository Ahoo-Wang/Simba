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
    fun `isInTtl is true before ttlAt`() {
        observedOwner("A", ttlAt = 10_000, transitionAt = 20_000, observedAt = 0).isInTtl.assert().isTrue()
    }

    @Test
    fun `isInTtl is false from ttlAt on`() {
        observedOwner("A", ttlAt = 100, transitionAt = 300, observedAt = 100).isInTtl.assert().isFalse()
        observedOwner("A", ttlAt = 50, transitionAt = 300, observedAt = 100).isInTtl.assert().isFalse()
    }

    @Test
    fun `isInTtl contenderId requires ownership and ttl`() {
        val owner = observedOwner("A", ttlAt = 10_000, transitionAt = 20_000, observedAt = 0)
        owner.isInTtl("A").assert().isTrue()
        owner.isInTtl("B").assert().isFalse()
        observedOwner("A", ttlAt = 50, transitionAt = 300, observedAt = 100).isInTtl("A").assert().isFalse()
    }

    @Test
    fun `hasOwner while the lease runs`() {
        observedOwner("A", ttlAt = 50, transitionAt = 10_000, observedAt = 100).hasOwner().assert().isTrue()
        observedOwner("A", ttlAt = 50, transitionAt = 50, observedAt = 100).hasOwner().assert().isFalse()
    }

    @Test
    fun `currentAt advances the observed backend time with the local clock`() {
        val owner = observedOwner("A", ttlAt = 0, transitionAt = 0, observedAt = 1_000_000)
        val first = owner.currentAt
        Thread.sleep(20)

        first.assert().isBetween(1_000_000, 1_000_005)
        owner.currentAt.assert().isGreaterThanOrEqualTo(first + 20)
    }

    @Test
    fun `equality covers lease facts, not observation time`() {
        val a = MutexOwner("A", 1, 2, 3, 4, observedAt = 10)
        val b = MutexOwner("A", 1, 2, 3, 4, observedAt = 20)

        a.assert().isEqualTo(b)
        a.hashCode().assert().isEqualTo(b.hashCode())
        a.assert().isNotEqualTo(MutexOwner("A", 1, 2, 3, 5))
    }

    @Test
    fun `default args yield a running lease`() {
        val owner = MutexOwner("A")
        owner.isInTtl.assert().isTrue()
        owner.hasOwner().assert().isTrue()
    }

    @Test
    fun `NONE constant has empty ownerId and is expired`() {
        MutexOwner.NONE.ownerId.assert().isEqualTo(MutexOwner.NONE_OWNER_ID)
        MutexOwner.NONE_OWNER_ID.assert().isEmpty()
        MutexOwner.NONE.isInTtl.assert().isFalse()
        MutexOwner.NONE.hasOwner().assert().isFalse()
    }
}
