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
import me.ahoo.simba.spi.Ticker
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Slot-routed calls (a Redis Cluster) on one Redis: lease-slot scripts that run long after the session-slot one (as
 * with a starved thread or a driver holding commands until it reconnects), simulated by moving a virtual [Ticker]
 * between the scripts, or by holding a script on real time; and lease slots whose node clock is skewed against the
 * session's, simulated by shifting `TIME` in their scripts.
 */
class RedisSlotDelayTest {
    private val redis = RedisLeaseFixture.redis
    private val lease = Duration.ofSeconds(30)
    private val shortLease = Duration.ofMillis(500)
    private val nanos = AtomicLong()
    private val jump = AtomicLong()

    // A jump set by [delayAfterAnswer] applies after the next reading: the backend reads the time a call answered,
    // then finds the delay passed before it builds the next script.
    private val virtual = Ticker { nanos.get().also { nanos.addAndGet(jump.getAndSet(0)) } }
    private val hooked = HookedScripting(RedisLeaseFixture.scripting)
    private val backend = routed(hooked, virtual)
    private val executor = Executors.newCachedThreadPool { task ->
        Thread(task, "redis-slot-delay-test").apply { isDaemon = true }
    }

    @AfterEach
    fun close() {
        executor.shutdownNow()
    }

    @Test
    fun anAcquireSendsNothingToLeaseSlotsOnceTheSessionMayHaveExpired() {
        val name = name()
        val session = backend.openSession(shortLease).id
        hooked.after = { call -> if (call.isSessionCall("lease_acquire")) advance(shortLease) }

        assertThrows<SimbaException> { backend.acquire(session, setOf(name)) }

        redis.hasKey(RedisLeaseKeys.lease(name)).assert().isFalse()
    }

    @Test
    fun anAcquireAnsweringLaterThanTheSessionsRemainingTimeReleasesWhatItBegan() {
        val name = name()
        val session = backend.openSession(lease).id
        hooked.before = { call -> if (call.isSlotCall("lease_acquire")) advance(lease) }

        assertThrows<SimbaException> { backend.acquire(session, setOf(name)) }

        hooked.before = {}
        redis.opsForHash<String, String>().get(RedisLeaseKeys.lease(name), "session").assert().isEqualTo("")
        backend.acquire(backend.openSession(lease).id, setOf(name))
            .assert().isEqualTo(mapOf(name to AcquireResult.Granted(2)))
    }

    @Test
    fun aLateAcquireWhoseReleaseFailsStillFailsAndLeavesOnlyWhatItBegan() {
        val free = name()
        val taken = name()
        val other = backend.openSession(lease).id
        val token = backend.granted(other, taken)
        val session = backend.openSession(lease).id
        hooked.before = { call ->
            if (call.isSlotCall("lease_acquire")) advance(lease)
            check(call.script.name != "lease_release") { "Connection lost before the release." }
        }

        assertThrows<SimbaException> { backend.acquire(session, setOf(free, taken)) }

        // The release failed: the late term stays until it expires on its own; the other session's is untouched.
        redis.opsForHash<String, String>().get(RedisLeaseKeys.lease(free), "session").assert().isEqualTo(session.value)
        hooked.before = {}
        backend.acquire(backend.openSession(lease).id, setOf(taken))
            .assert().isEqualTo(mapOf(taken to AcquireResult.HeldBy(other, token)))
    }

    @Test
    fun keepAliveNeverLosesATermALateAcquireBegan() {
        val held = CompletableFuture<Unit>()
        val reached = CompletableFuture<Unit>()
        val real = HookedScripting(RedisLeaseFixture.scripting)
        real.before = { call ->
            if (call.isSlotCall("lease_acquire") && reached.complete(Unit)) {
                held.get(30, TimeUnit.SECONDS)
            }
        }
        val plain = routed(RedisLeaseFixture.scripting, Ticker.SYSTEM)
        val name = name()
        val key = RedisLeaseKeys.lease(name)
        val session = plain.openSession(shortLease).id
        val index = RedisLeaseKeys.sessionLeases(session)

        val acquiring = async { routed(real, Ticker.SYSTEM).acquire(session, setOf(name)) }
        reached.get(30, TimeUnit.SECONDS)
        // The session stays live; keep-alive drops the name from its index one lease after the attempt.
        awaitUntil {
            plain.keepAlive(session, shortLease, emptySet())
            redis.opsForZSet().score(index, key) == null
        }
        held.complete(Unit)

        assertThrows<SimbaException> { acquiring.await() }
        plain.keepAlive(
            session,
            shortLease,
            emptySet()
        ).assert().isEqualTo(KeepAliveResult.Live(shortLease, emptyMap()))
        redis.opsForHash<String, String>().get(key, "session").assert().isEqualTo("")
    }

    @Test
    fun aDelayedAcquireStillGrantsOneFullSessionLease() {
        val name = name()
        val session = backend.openSession(lease).id
        hooked.after = { call -> if (call.isSessionCall("lease_acquire")) advance(Duration.ofSeconds(10)) }
        var granted = 0L
        hooked.before = { call -> if (call.isSlotCall("lease_acquire")) granted = call.args[2].toLong() }
        val before = redisTime()

        backend.acquire(session, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.Granted(1)))

        granted.assert().isEqualTo(lease.toMillis())
        expires(name).assert().isBetween(before + granted, redisTime() + granted)
    }

    @Test
    fun aKeepAliveBetweenTheSessionCallAndTheLeaseCallNeverOutlivesTheGrant() {
        val plain = routed(RedisLeaseFixture.scripting, Ticker.SYSTEM)
        val real = HookedScripting(RedisLeaseFixture.scripting)
        val name = name()
        val session = plain.openSession(lease).id
        // As if the session's last keep-alive ran most of a lease ago.
        redis.expire(RedisLeaseKeys.session(session), Duration.ofSeconds(2)).assert().isTrue()
        var keptAlive: KeepAliveResult? = null
        real.before = { call ->
            if (call.isSlotCall("lease_acquire") && keptAlive == null) {
                keptAlive = plain.keepAlive(session, lease, emptySet())
            }
        }

        routed(
            real,
            Ticker.SYSTEM
        ).acquire(session, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.Granted(1)))

        // The engine adopts the term with the deadline of that keep-alive: the lease must last as long as the session.
        keptAlive.assert().isEqualTo(KeepAliveResult.Live(lease, emptyMap()))
        val leaseLeft = expires(name) - redisTime()
        leaseLeft.assert().isGreaterThanOrEqualTo(
            redis.getExpire(RedisLeaseKeys.session(session), TimeUnit.MILLISECONDS)
        )
    }

    @Test
    fun leaseSlotsWhoseClocksRunAtAnotherRateWithinTheDriftStillCoverTheTerm() {
        val base = redisTime()
        listOf(1 + DRIFT, 1 - DRIFT).forEach { rate ->
            val clocked = routed(SkewedScripting(RedisLeaseFixture.scripting, 0, rate, base), Ticker.SYSTEM)
            val name = name()
            val group = group()
            val session = clocked.openSession(lease).id

            val token = clocked.granted(session, name)
            clocked.join(session, group)
            clocked.keepAlive(session, lease, emptySet())
                .assert().isEqualTo(KeepAliveResult.Live(lease, mapOf(name to token)))

            // Real time left on the lease covers the engine's local validity of the session: lease × (1 − drift).
            val slotNow = base + Math.floor((redisTime() - base) * rate).toLong()
            val leaseLeft = (expires(name) - slotNow) / rate
            val sessionLeft = redis.getExpire(RedisLeaseKeys.session(session), TimeUnit.MILLISECONDS)
            leaseLeft.assert().isGreaterThanOrEqualTo(sessionLeft * (1 - DRIFT))
            clocked.members(group).assert().containsExactly(session)
        }
    }

    @Test
    fun aDelayedKeepAliveExtendsOnlyWhatTheSessionHasLeft() {
        val name = name()
        val session = backend.openSession(shortLease).id
        val token = backend.granted(session, name)
        val delay = Duration.ofSeconds(10)
        var left = 0L
        hooked.after = { call -> if (call.script.name == "lease_session_keep_alive") delayAfterAnswer(delay) }
        hooked.before = { call -> if (call.isSlotCall("lease_keep_alive")) left = call.args[4].toLong() }
        val before = redisTime()

        backend.keepAlive(
            session,
            lease,
            emptySet()
        ).assert().isEqualTo(KeepAliveResult.Live(lease, mapOf(name to token)))

        left.assert().isEqualTo(lease.minus(delay).toMillis())
        expires(name).assert().isBetween(before + left, redisTime() + left)
    }

    @Test
    fun aKeepAliveWhoseSessionMayHaveExpiredExtendsNothing() {
        val name = name()
        val session = backend.openSession(lease).id
        val token = backend.granted(session, name)
        val granted = expires(name)
        val group = group()
        backend.join(session, group)
        val member = redis.opsForZSet().score(RedisLeaseKeys.group(group), session.value)
        hooked.after = { call -> if (call.script.name == "lease_session_keep_alive") delayAfterAnswer(lease) }

        backend.keepAlive(
            session,
            lease,
            emptySet()
        ).assert().isEqualTo(KeepAliveResult.Live(lease, mapOf(name to token)))

        expires(name).assert().isEqualTo(granted)
        redis.opsForZSet().score(RedisLeaseKeys.group(group), session.value).assert().isEqualTo(member)
    }

    @Test
    fun aJoinSendsNothingToTheGroupSlotOnceTheSessionMayHaveExpired() {
        val group = group()
        val session = backend.openSession(shortLease).id
        hooked.after = { call -> if (call.isSessionCall("lease_group_join")) advance(shortLease) }

        assertThrows<SimbaException> { backend.join(session, group) }

        redis.hasKey(RedisLeaseKeys.group(group)).assert().isFalse()
    }

    @Test
    fun leaseSlotsWhoseClocksAreSkewedBeyondALeaseStillServeTheSessionForItsTime() {
        listOf(SKEW_SECONDS, -SKEW_SECONDS).forEach { skew ->
            val skewed = routed(
                SkewedScripting(RedisLeaseFixture.scripting, skew * MILLIS_PER_SECOND, 1.0, 0),
                Ticker.SYSTEM
            )
            val name = name()
            val group = group()
            val session = skewed.openSession(lease).id

            val token = skewed.granted(session, name)
            skewed.join(session, group)

            // The lease, measured on its own skewed node, lasts at least as long as the session on its node.
            val leaseLeft = expires(name) - (redisTime() + skew * MILLIS_PER_SECOND)
            val sessionLeft = redis.getExpire(RedisLeaseKeys.session(session), TimeUnit.MILLISECONDS)
            leaseLeft.assert().isGreaterThanOrEqualTo(sessionLeft)
            val granted = expires(name)
            skewed.keepAlive(session, lease, emptySet())
                .assert().isEqualTo(KeepAliveResult.Live(lease, mapOf(name to token)))
            expires(name).assert().isGreaterThanOrEqualTo(granted)
            skewed.members(group).assert().containsExactly(session)
            skewed.acquire(skewed.openSession(lease).id, setOf(name))
                .assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(session, token)))
        }
    }

    private fun delayAfterAnswer(duration: Duration) {
        jump.set(duration.toNanos())
    }

    private fun advance(duration: Duration) {
        nanos.addAndGet(duration.toNanos())
    }

    private fun routed(scripting: RedisScripting, ticker: Ticker): RedisLeaseBackend =
        RedisLeaseBackend(SlotCheckingScripting(scripting), null, true, ticker)

    private fun RedisLeaseBackend.granted(session: SessionId, name: LeaseName): Long =
        (acquire(session, setOf(name)).getValue(name) as AcquireResult.Granted).token

    private fun expires(name: LeaseName): Long =
        redis.opsForHash<String, String>().get(RedisLeaseKeys.lease(name), "expires")!!.toLong()

    private fun redisTime(): Long = redis.execute(RedisCallback { it.serverCommands().time(TimeUnit.MILLISECONDS) })!!

    private fun name(): LeaseName = LeaseName("redis.${UUID.randomUUID()}")

    private fun group(): String = "group.${UUID.randomUUID()}"

    private fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block, executor)

    private fun <T> CompletableFuture<T>.await(): T =
        try {
            get(30, TimeUnit.SECONDS)
        } catch (error: ExecutionException) {
            throw error.cause!!
        }

    private fun awaitUntil(condition: () -> Boolean) {
        val done = CompletableFuture<Unit>()
        val poller = Executors.newSingleThreadScheduledExecutor()
        poller.scheduleWithFixedDelay({
            try {
                if (condition()) {
                    done.complete(Unit)
                }
            } catch (error: Throwable) {
                done.completeExceptionally(error)
            }
        }, 0, POLL_MILLIS, TimeUnit.MILLISECONDS)
        try {
            done.get(30, TimeUnit.SECONDS)
        } finally {
            poller.shutdownNow()
        }
    }

    /** A script call that carries the session keys (the session slot's), else one on a lease or group slot. */
    private fun ScriptCall.isSessionCall(name: String): Boolean = script.name == name && args.first() == "1"

    private fun ScriptCall.isSlotCall(name: String): Boolean = script.name == name && args.first() == "0"

    /** Runs [before] and [after] around every script call it passes to [delegate]. */
    private class HookedScripting(private val delegate: RedisScripting) : RedisScripting by delegate {
        @Volatile
        var before: (ScriptCall) -> Unit = {}

        @Volatile
        var after: (ScriptCall) -> Unit = {}

        override fun eval(calls: List<ScriptCall>, wait: ReplicaWait?): ScriptReplies {
            calls.forEach { before(it) }
            return delegate.eval(calls, wait).also { calls.forEach { after(it) } }
        }
    }

    /**
     * Turns Redis `TIME` `t` (in milliseconds) into `base + (t − base) × rate + offset` in every script on lease and
     * group keys only, as if their nodes' clocks ran that far from, or at another rate than, the session's node.
     */
    private class SkewedScripting(
        private val delegate: RedisScripting,
        private val offset: Long,
        private val rate: Double,
        private val base: Long,
    ) : RedisScripting by delegate {
        override fun eval(calls: List<ScriptCall>, wait: ReplicaWait?): ScriptReplies =
            delegate.eval(calls.map(::skew), wait)

        private fun skew(call: ScriptCall): ScriptCall {
            val sessionless = call.keys.isNotEmpty() && call.keys.none { it.startsWith("simba:session:") }
            if (!sessionless || TIME !in call.script.source) {
                return call
            }
            val shifted = TIME + "\n" +
                "local skewed = $base + math.floor((tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000) " +
                "- $base) * $rate) + ($offset)\n" +
                "time = { tostring(math.floor(skewed / 1000)), tostring((skewed % 1000) * 1000) }"
            val script = LeaseScript("${call.script.name}+$offset*$rate", call.script.source.replace(TIME, shifted))
            return ScriptCall(script, call.keys, call.args)
        }
    }

    private companion object {
        const val POLL_MILLIS = 10L
        const val SKEW_SECONDS = 60L
        const val MILLIS_PER_SECOND = 1000L
        const val DRIFT = 0.01
        const val TIME = "local time = redis.call('TIME')"
    }
}
