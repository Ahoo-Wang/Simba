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

package me.ahoo.simba.redis

import me.ahoo.simba.SimbaException
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.RedisCallback
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Slot-routed calls (a Redis Cluster) whose lease-slot script reaches Redis long after the session-slot one, as with a
 * starved thread or a driver holding commands until it reconnects. [HeldScripting] holds that script; the test lets
 * it go once Redis time passed the point under test.
 */
class RedisSlotDelayTest {
    private val redis = RedisLeaseFixture.redis
    private val plain = routed(RedisLeaseFixture.scripting)
    private val lease = Duration.ofSeconds(30)
    private val shortLease = Duration.ofMillis(500)
    private val executor = Executors.newCachedThreadPool {
        Thread(
            it,
            "redis-slot-delay-test"
        ).apply { isDaemon = true }
    }

    @AfterEach
    fun close() {
        executor.shutdownNow()
    }

    @Test
    fun anAcquireReachingTheLeaseSlotAfterTheSessionLeaseGrantsNothing() {
        val held = HeldScripting(RedisLeaseFixture.scripting, "lease_acquire")
        val name = name()
        val session = plain.openSession(shortLease).id

        val acquiring = async { routed(held).acquire(session, setOf(name)) }
        val deadline = held.call().args[3].toLong()
        // Past the deadline, and past the session's expiry (no later than the deadline) by a millisecond.
        awaitUntil { redisTime() > deadline }
        held.release()

        assertThrows<SimbaException> { acquiring.await() }
        redis.hasKey(RedisLeaseKeys.session(session)).assert().isFalse()
        redis.hasKey(RedisLeaseKeys.lease(name)).assert().isFalse()
        plain.acquire(
            plain.openSession(lease).id,
            setOf(name)
        ).assert().isEqualTo(mapOf(name to AcquireResult.Granted(1)))
    }

    @Test
    fun keepAliveNeverForgetsANameALateAcquireCouldStillTake() {
        val held = HeldScripting(RedisLeaseFixture.scripting, "lease_acquire")
        val name = name()
        val key = RedisLeaseKeys.lease(name)
        val session = plain.openSession(shortLease).id
        val index = RedisLeaseKeys.sessionLeases(session)

        val acquiring = async { routed(held).acquire(session, setOf(name)) }
        val deadline = held.call().args[3].toLong()
        // The session stays live; keep-alive drops the name from its index once no acquire can take it any more.
        awaitUntil {
            plain.keepAlive(session, shortLease, emptySet())
            redis.opsForZSet().score(index, key) == null
        }
        redisTime().assert().isGreaterThanOrEqualTo(deadline)
        held.release()

        assertThrows<SimbaException> { acquiring.await() }
        plain.keepAlive(
            session,
            shortLease,
            emptySet()
        ).assert().isEqualTo(KeepAliveResult.Live(shortLease, emptyMap()))
        redis.hasKey(key).assert().isFalse()
    }

    @Test
    fun aDelayedAcquireLastsNoLongerThanTheSessionCallAllows() {
        val held = HeldScripting(RedisLeaseFixture.scripting, "lease_acquire")
        val name = name()
        val session = plain.openSession(lease).id

        val acquiring = async { routed(held).acquire(session, setOf(name)) }
        val deadline = held.call().args[3].toLong()
        awaitUntil { redisTime() > deadline - lease.toMillis() + DELAY_MILLIS }
        held.release()

        acquiring.await().assert().isEqualTo(mapOf(name to AcquireResult.Granted(1)))
        expires(name).assert().isEqualTo(deadline)
    }

    @Test
    fun aDelayedKeepAliveExtendsNoLeaseBeyondTheSessionsNewExpiry() {
        val held = HeldScripting(RedisLeaseFixture.scripting, "lease_keep_alive")
        val name = name()
        val session = plain.openSession(lease).id
        val token = plain.granted(session, name)

        val keepingAlive = async { routed(held).keepAlive(session, lease, emptySet()) }
        val until = held.call().args[4].toLong()
        awaitUntil { redisTime() > until - lease.toMillis() + DELAY_MILLIS }
        held.release()

        keepingAlive.await().assert().isEqualTo(KeepAliveResult.Live(lease, mapOf(name to token)))
        expires(name).assert().isEqualTo(until)
    }

    @Test
    fun aKeepAliveReachingALeaseSlotAfterTheSessionDiedExtendsNothing() {
        val held = HeldScripting(RedisLeaseFixture.scripting, "lease_keep_alive")
        val name = name()
        val session = plain.openSession(shortLease).id
        val token = plain.granted(session, name)

        val keepingAlive = async { routed(held).keepAlive(session, shortLease, emptySet()) }
        val until = held.call().args[4].toLong()
        // The holder acquires again half a lease later, so the lease outlives the session's expiry `until`.
        awaitUntil { redisTime() >= until - shortLease.toMillis() / 2 }
        plain.granted(session, name).assert().isEqualTo(token)
        val extended = expires(name)
        awaitUntil { redisTime() > until }
        held.release()

        // Still held when the call ran, yet not pushed a lease past it.
        keepingAlive.await().assert().isEqualTo(KeepAliveResult.Live(shortLease, mapOf(name to token)))
        expires(name).assert().isEqualTo(extended)
        redis.hasKey(RedisLeaseKeys.session(session)).assert().isFalse()
    }

    @Test
    fun aJoinReachingTheGroupSlotAfterTheSessionLeaseAddsNoMember() {
        val held = HeldScripting(RedisLeaseFixture.scripting, "lease_group_join")
        val group = "group.${UUID.randomUUID()}"
        val session = plain.openSession(shortLease).id

        val joining = async { routed(held).join(session, group) }
        val deadline = held.call().args[3].toLong()
        awaitUntil { redisTime() >= deadline }
        held.release()

        assertThrows<SimbaException> { joining.await() }
        redis.hasKey(RedisLeaseKeys.group(group)).assert().isFalse()
    }

    private fun routed(scripting: RedisScripting): RedisLeaseBackend =
        RedisLeaseBackend(SlotCheckingScripting(scripting), routeBySlot = true, maxClockSkew = Duration.ZERO)

    private fun RedisLeaseBackend.granted(session: SessionId, name: LeaseName): Long =
        (acquire(session, setOf(name)).getValue(name) as AcquireResult.Granted).token

    private fun expires(name: LeaseName): Long =
        redis.opsForHash<String, String>().get(RedisLeaseKeys.lease(name), "expires")!!.toLong()

    private fun redisTime(): Long = redis.execute(RedisCallback { it.serverCommands().time(TimeUnit.MILLISECONDS) })!!

    private fun name(): LeaseName = LeaseName("redis.${UUID.randomUUID()}")

    private fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block, executor)

    private fun <T> CompletableFuture<T>.await(): T =
        try {
            get(30, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause!!
        }

    private fun awaitUntil(condition: () -> Boolean) {
        val done = CompletableFuture<Unit>()
        val polling = Executors.newSingleThreadScheduledExecutor().also { poller ->
            poller.scheduleWithFixedDelay({
                try {
                    if (condition()) {
                        done.complete(Unit)
                    }
                } catch (error: Throwable) {
                    done.completeExceptionally(error)
                }
            }, 0, POLL_MILLIS, TimeUnit.MILLISECONDS)
        }
        try {
            done.get(30, TimeUnit.SECONDS)
        } finally {
            polling.shutdownNow()
        }
    }

    /**
     * Holds the first batch with a lease-slot call (a call without the session keys) of [script] until [release]d,
     * then runs it through [delegate].
     */
    private class HeldScripting(private val delegate: RedisScripting, private val script: String) :
        RedisScripting by delegate {
        private val held = CompletableFuture<ScriptCall>()
        private val released = CompletableFuture<Unit>()

        override fun eval(calls: List<ScriptCall>, wait: ReplicaWait?): ScriptReplies {
            val call = calls.firstOrNull { it.script.name == script && it.args.first() == "0" }
            if (call != null && held.complete(call)) {
                released.get(30, TimeUnit.SECONDS)
            }
            return delegate.eval(calls, wait)
        }

        /** The held call, once it was reached. */
        fun call(): ScriptCall = held.get(30, TimeUnit.SECONDS)

        fun release() {
            released.complete(Unit)
        }
    }

    private companion object {
        const val POLL_MILLIS = 10L
        const val DELAY_MILLIS = 50L
    }
}
