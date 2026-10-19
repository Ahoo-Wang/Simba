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
import me.ahoo.simba.spi.BackendHint
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionExpiredException
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.connection.RedisConnection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Edge cases of the Lua scripts behind [RedisLeaseBackend], checked against the keys they leave in Redis. */
class RedisLeaseBackendTest {
    private val redis = RedisLeaseFixture.redis
    private val backend = RedisLeaseBackend(RedisLeaseFixture.scripting)
    private val lease = Duration.ofSeconds(30)
    private val shortLease = Duration.ofMillis(500)
    private val poller = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "redis-lease-test").apply { isDaemon = true }
    }

    @AfterEach
    fun close() {
        poller.shutdownNow()
    }

    @Test
    fun theTokenNeverGoesBackAndTheLeaseKeyNeverExpires() {
        val name = name()
        val key = RedisLeaseKeys.lease(name)
        val first = backend.openSession(shortLease).id
        backend.acquire(first, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.Granted(1)))
        redis.getExpire(key).assert().isEqualTo(-1L)

        val second = backend.openSession(lease).id
        awaitUntil { backend.acquire(second, setOf(name)).getValue(name) == AcquireResult.Granted(2) }
        backend.release(second, mapOf(name to 2))
        backend.closeSession(second)

        redis.hasKey(RedisLeaseKeys.session(first)).assert().isFalse()
        redis.getExpire(key).assert().isEqualTo(-1L)
        redis.opsForHash<String, String>().entries(key).assert().isEqualTo(
            mapOf("session" to "", "token" to "2", "expires" to "0")
        )
        backend.acquire(backend.openSession(lease).id, setOf(name))
            .assert().isEqualTo(mapOf(name to AcquireResult.Granted(3)))
    }

    @Test
    fun aReleaseAfterTheLeaseExpiredNeverFreesTheNextTerm() {
        val name = name()
        val expiring = backend.openSession(shortLease).id
        val contender = backend.openSession(lease).id
        backend.acquire(expiring, setOf(name)).assert().isEqualTo(mapOf(name to AcquireResult.Granted(1)))
        awaitUntil { backend.acquire(contender, setOf(name)).getValue(name) == AcquireResult.Granted(2) }

        backend.release(expiring, mapOf(name to 1))
        backend.release(expiring, mapOf(name to 2))

        backend.acquire(backend.openSession(lease).id, setOf(name))
            .assert().isEqualTo(mapOf(name to AcquireResult.HeldBy(contender, 2)))
        backend.keepAlive(contender, lease, setOf(name)).assert()
            .isEqualTo(KeepAliveResult.Live(lease, mapOf(name to 2)))
    }

    @Test
    fun keepAliveNeverRevivesAnExpiredLease() {
        val name = name()
        val session = backend.openSession(lease).id
        val token = backend.granted(session, name)
        redis.opsForHash<String, String>().put(RedisLeaseKeys.lease(name), "expires", "1")

        backend.keepAlive(session, lease, setOf(name)).assert().isEqualTo(KeepAliveResult.Live(lease, emptyMap()))
        backend.acquire(backend.openSession(lease).id, setOf(name)).getValue(name)
            .assert().isEqualTo(AcquireResult.Granted(token + 1))
    }

    @Test
    fun keepAliveAcrossSlotsRunsOneScriptPerSlot() {
        val scripting = SlotCheckingScripting(RedisLeaseFixture.scripting)
        val routed = RedisLeaseBackend(scripting, routeBySlot = true)
        val names = generateSequence { name() }.distinctBy { RedisSlots.slot(RedisLeaseKeys.lease(it)) }.take(3).toSet()
        val session = routed.openSession(lease).id
        val group = "group.${UUID.randomUUID()}"
        routed.join(session, group)
        val tokens = names.associateWith { routed.granted(session, it) }
        val groupSlot = RedisSlots.slot(RedisLeaseKeys.group(group))
        val slots = (names.map { RedisSlots.slot(RedisLeaseKeys.lease(it)) } + groupSlot).toSet()
        scripting.calls.set(0)

        val result = routed.keepAlive(session, lease, names + name())

        result.assert().isEqualTo(KeepAliveResult.Live(lease, tokens))
        scripting.calls.get().assert().isEqualTo(1 + slots.size + 1)
        names.forEach {
            redis.getExpire(RedisLeaseKeys.lease(it)).assert().isEqualTo(-1L)
        }
        routed.members(group).assert().containsExactly(session)
    }

    @Test
    fun anUnreplicatedTermIsReleasedAndTheAcquisitionFails() {
        val waiting = RedisLeaseBackend(RedisLeaseFixture.scripting, ReplicaWait(1, Duration.ofMillis(50)))
        val owned = name()
        val fresh = name()
        val session = waiting.openSession(lease).id
        backend.acquire(session, setOf(owned)).assert().isEqualTo(mapOf(owned to AcquireResult.Granted(1)))

        waiting.acquire(session, setOf(owned)).assert().isEqualTo(mapOf(owned to AcquireResult.Granted(1)))
        assertThrows<SimbaException> { waiting.acquire(session, setOf(fresh)) }

        backend.acquire(backend.openSession(lease).id, setOf(fresh, owned)).assert().isEqualTo(
            mapOf(fresh to AcquireResult.Granted(2), owned to AcquireResult.HeldBy(session, 1))
        )
    }

    @Test
    fun waitReplicasIsRejectedOnACluster() {
        val cluster = object : RedisScripting by RedisLeaseFixture.scripting {
            override val clustered: Boolean = true
        }

        assertThrows<IllegalArgumentException> { RedisLeaseBackend(cluster, ReplicaWait(1, Duration.ofSeconds(1))) }
    }

    @Test
    fun scriptsMissingOnTheServerAreSentAgain() {
        RedisLeaseFixture.connectionFactory.connection.use { connection: RedisConnection ->
            connection.scriptingCommands().scriptFlush()
        }

        val session = backend.openSession(lease, mapOf("host" to "a")).id

        redis.opsForHash<String, String>().get(RedisLeaseKeys.session(session), "meta:host").assert().isEqualTo("a")
        redis.getExpire(RedisLeaseKeys.session(session), TimeUnit.MILLISECONDS).assert().isPositive()
    }

    @Test
    fun hintsIgnoreOtherMessagesOnLeaseChannels() {
        val name = name()
        val session = backend.openSession(lease).id
        val token = backend.granted(session, name)
        val hints = LinkedBlockingQueue<BackendHint>()

        backend.subscribe(hints::add).use {
            redis.convertAndSend(RedisLeaseKeys.lease(name), "acquired")
            redis.convertAndSend("simba:lease:{bad name}", "released")
            backend.release(session, mapOf(name to token))

            hints.poll(10, TimeUnit.SECONDS).assert().isEqualTo(BackendHint.Released(name))
        }
    }

    @Test
    fun groupNamesFollowTheLeaseNameRules() {
        val session = backend.openSession(lease).id

        assertThrows<IllegalArgumentException> { backend.join(session, "a{b}") }
        assertThrows<IllegalArgumentException> { backend.members("") }
    }

    @Test
    fun anUnknownSessionCannotJoinOnTheSlotRoutedPath() {
        val routed = RedisLeaseBackend(RedisLeaseFixture.scripting, routeBySlot = true)

        assertThrows<SessionExpiredException> { routed.join(SessionId.random(), "group.unknown") }
    }

    private fun RedisLeaseBackend.granted(session: SessionId, name: LeaseName): Long =
        (acquire(session, setOf(name)).getValue(name) as AcquireResult.Granted).token

    private fun name(): LeaseName = LeaseName("redis.${UUID.randomUUID()}")

    private fun awaitUntil(condition: () -> Boolean) {
        val done = CompletableFuture<Unit>()
        val polling = poller.scheduleWithFixedDelay({
            try {
                if (condition()) {
                    done.complete(Unit)
                }
            } catch (error: Throwable) {
                done.completeExceptionally(error)
            }
        }, 0, 20, TimeUnit.MILLISECONDS)
        try {
            done.get(30, TimeUnit.SECONDS)
        } finally {
            polling.cancel(false)
        }
    }
}
