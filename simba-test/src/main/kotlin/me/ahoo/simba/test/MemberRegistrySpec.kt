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
package me.ahoo.simba.test

import me.ahoo.simba.partition.MemberRegistry
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport

/**
 * TCK every [MemberRegistry] must pass (ADR 0009).
 */
abstract class MemberRegistrySpec {
    companion object {
        private val LONG_TTL: Duration = Duration.ofMinutes(1)
        private const val CONCURRENT_MEMBERS = 16
        private const val TIMEOUT_SECONDS = 30L
    }

    abstract val memberRegistry: MemberRegistry

    /**
     * Whether memberships expire after their `ttl` (Zookeeper's end with the session instead).
     */
    open val expiresAfterTtl: Boolean = true

    private fun newGroup(): String = "group-${UUID.randomUUID()}"

    @Test
    open fun joinedMembersAreListedSorted() {
        val group = newGroup()
        memberRegistry.heartbeat(group, "b", LONG_TTL)
        memberRegistry.heartbeat(group, "a", LONG_TTL)

        memberRegistry.members(group).assert().containsExactly("a", "b")
    }

    @Test
    open fun groupsAreIsolated() {
        val group = newGroup()
        memberRegistry.heartbeat(group, "a", LONG_TTL)

        memberRegistry.members(newGroup()).assert().isEmpty()
    }

    @Test
    open fun heartbeatRefreshesWithoutDuplicates() {
        val group = newGroup()
        memberRegistry.heartbeat(group, "a", LONG_TTL)
        memberRegistry.heartbeat(group, "a", LONG_TTL)

        memberRegistry.members(group).assert().containsExactly("a")
    }

    @Test
    open fun leaveRemovesOnlyThatMember() {
        val group = newGroup()
        memberRegistry.heartbeat(group, "a", LONG_TTL)
        memberRegistry.heartbeat(group, "b", LONG_TTL)

        memberRegistry.leave(group, "a")
        memberRegistry.leave(group, "unknown")

        memberRegistry.members(group).assert().containsExactly("b")
    }

    @Test
    open fun membershipExpiresWithoutHeartbeat() {
        Assumptions.assumeTrue(expiresAfterTtl)
        val group = newGroup()
        memberRegistry.heartbeat(group, "short", Duration.ofMillis(200))
        memberRegistry.heartbeat(group, "long", LONG_TTL)
        memberRegistry.members(group).assert().containsExactly("long", "short")

        // Backend time decides expiry, so this can only wait and look again.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (memberRegistry.members(group) != listOf("long") && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50))
        }

        memberRegistry.members(group).assert().containsExactly("long")
    }

    @Test
    open fun concurrentJoinsAreAllListed() {
        val group = newGroup()
        val executor = Executors.newFixedThreadPool(CONCURRENT_MEMBERS)
        try {
            val ready = CountDownLatch(CONCURRENT_MEMBERS)
            val go = CountDownLatch(1)
            val joins = (0 until CONCURRENT_MEMBERS).map { index ->
                executor.submit {
                    ready.countDown()
                    go.await()
                    memberRegistry.heartbeat(group, "member-%02d".format(index), LONG_TTL)
                }
            }
            ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS).assert().isTrue()
            go.countDown()
            joins.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }

            memberRegistry.members(group).assert()
                .isEqualTo((0 until CONCURRENT_MEMBERS).map { "member-%02d".format(it) })
        } finally {
            executor.shutdownNow()
        }
    }
}
