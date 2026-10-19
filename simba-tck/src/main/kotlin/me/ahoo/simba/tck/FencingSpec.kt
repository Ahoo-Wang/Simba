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
package me.ahoo.simba.tck

import me.ahoo.simba.Simba
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * TCK for a protected resource that checks fencing tokens ([me.ahoo.simba.Term.token], ADR 0002, 0010): extend it and
 * implement [write] and [highestToken] against your resource; override [leaseBackend] to also run the resource
 * against real terms of that backend.
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
        private const val LOCK_NAME_LENGTH = 64
        private val ACQUIRE_TIMEOUT: Duration = Duration.ofSeconds(TIMEOUT_SECONDS)
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

    /** The backend whose terms [staleTermIsRejectedAfterTheLockMovedOn] writes with; `null` skips that test. */
    open fun leaseBackend(): LeaseBackend? = null

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

    /**
     * Two clients take turns on one lock: once the second term wrote, a write carrying the first (ended) term's token
     * is rejected, as a paused former owner's would be.
     */
    @Test
    open fun staleTermIsRejectedAfterTheLockMovedOn() {
        val backend = leaseBackend()
        assumeTrue(backend != null, "no lease backend")
        val resource = newResource()
        val lockName = resource.filter { it.isLetterOrDigit() || it == '-' }.take(LOCK_NAME_LENGTH)
        Simba.builder().backend(backend!!).build().use { first ->
            Simba.builder().backend(backend).build().use { second ->
                val stale = first.lock(lockName).acquire(ACQUIRE_TIMEOUT)
                write(resource, stale.token).assert().isTrue()
                stale.close()

                second.lock(lockName).acquire(ACQUIRE_TIMEOUT).use { current ->
                    (current.token > stale.token).assert().isTrue()
                    write(resource, current.token).assert().isTrue()
                    write(resource, stale.token).assert().isFalse()
                    highestToken(resource).assert().isEqualTo(current.token)
                }
            }
        }
    }
}
