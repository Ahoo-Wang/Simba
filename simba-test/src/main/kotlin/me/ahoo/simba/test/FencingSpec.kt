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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TCK for a protected resource that checks fencing tokens (ADR 0002, 0007): extend it and implement [write] and
 * [highestToken] against your resource.
 *
 * A correct resource accepts a write whose token is at least the highest it has accepted (renewals and retries
 * within one term keep the token) and rejects lower ones, atomically with the write itself: a check followed by a
 * separate write lets a stale owner land after a newer one.
 */
abstract class FencingSpec {
    companion object {
        private const val WRITERS = 16
        private const val ROUNDS = 20
        private const val TIMEOUT_SECONDS = 30L
    }

    /**
     * Writes to [resource] carrying [fencingToken] (always positive); returns whether the resource accepted it.
     * A [resource] the resource has never seen starts empty.
     */
    abstract fun write(resource: String, fencingToken: Long): Boolean

    /**
     * The token of the last write [resource] accepted, `0` when none.
     */
    abstract fun highestToken(resource: String): Long

    /**
     * A resource name not used before.
     */
    open fun newResource(): String = "fencing-${UUID.randomUUID()}"

    @Test
    open fun acceptsIncreasingTokens() {
        val resource = newResource()
        write(resource, 1).assert().isTrue()
        write(resource, 2).assert().isTrue()
        write(resource, 5).assert().isTrue()
        highestToken(resource).assert().isEqualTo(5)
    }

    @Test
    open fun acceptsTheSameTokenAgain() {
        val resource = newResource()
        write(resource, 3).assert().isTrue()
        write(resource, 3).assert().isTrue()
        highestToken(resource).assert().isEqualTo(3)
    }

    @Test
    open fun rejectsLowerToken() {
        val resource = newResource()
        write(resource, 7).assert().isTrue()
        write(resource, 6).assert().isFalse()
        highestToken(resource).assert().isEqualTo(7)
    }

    /**
     * Writers with distinct tokens race on one resource: the highest token must always end up winning, whatever
     * the interleaving.
     */
    @Test
    open fun concurrentWritersNeverLetALowerTokenWin() {
        val executor = Executors.newFixedThreadPool(WRITERS)
        try {
            repeat(ROUNDS) {
                val resource = newResource()
                val ready = CountDownLatch(WRITERS)
                val go = CountDownLatch(1)
                val futures = (1..WRITERS).shuffled().map { token ->
                    executor.submit<Boolean> {
                        ready.countDown()
                        go.await()
                        write(resource, token.toLong())
                    }
                }
                ready.await(TIMEOUT_SECONDS, TimeUnit.SECONDS).assert().isTrue()
                go.countDown()
                val accepted = futures.count { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
                accepted.assert().isGreaterThanOrEqualTo(1)
                highestToken(resource).assert().isEqualTo(WRITERS.toLong())
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
