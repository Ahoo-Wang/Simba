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

package me.ahoo.simba.testing

import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.BackendHint
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionExpiredException
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class InMemoryLeaseBackendTest {
    private val clock = VirtualClock()
    private var ids = 0
    private val backend = InMemoryLeaseBackend(clock) { SessionId("s${++ids}") }
    private val hints = ArrayList<BackendHint>()
    private val lease = Duration.ofSeconds(3)
    private val name = LeaseName("orders")

    init {
        backend.subscribe { hints += it }
    }

    @Test
    fun `a lease moves only when its session expires or releases it, keeping its token`() {
        val first = backend.openSession(lease).id
        val second = backend.openSession(lease).id
        backend.capabilities.releaseHints.assert().isTrue()

        backend.acquire(first, setOf(name))[name].assert().isEqualTo(AcquireResult.Granted(1))
        backend.acquire(first, setOf(name))[name].assert().isEqualTo(AcquireResult.Granted(1))
        backend.acquire(second, setOf(name))[name].assert().isEqualTo(AcquireResult.HeldBy(first, 1))
        backend.owner(name).assert().isEqualTo(InMemoryLeaseBackend.Owner(first, 1))

        backend.release(first, mapOf(name to 2L))
        backend.owner(name).assert().isNotNull()
        backend.release(first, mapOf(name to 1L, LeaseName("unknown") to 1L))
        hints.assert().contains(BackendHint.Released(name))
        backend.owner(name).assert().isNull()
        backend.acquire(second, setOf(name))[name].assert().isEqualTo(AcquireResult.Granted(2))
        backend.token(name).assert().isEqualTo(2L)
        backend.token(LeaseName("never")).assert().isEqualTo(0L)
    }

    @Test
    fun `keep-alive extends a live session and never revives an expired one`() {
        val session = backend.openSession(lease).id
        backend.acquire(session, setOf(name))
        clock.advance(Duration.ofSeconds(2))
        val live = backend.keepAlive(session, lease, setOf(name)) as KeepAliveResult.Live
        live.owned.assert().isEqualTo(mapOf(name to 1L))
        clock.advance(Duration.ofSeconds(3))

        backend.keepAlive(session, lease, setOf(name)).assert().isEqualTo(KeepAliveResult.Expired)
        assertThrows<SessionExpiredException> { backend.acquire(session, setOf(name)) }
        assertThrows<SessionExpiredException> { backend.join(session, "g") }
        backend.owner(name).assert().isNull()
    }

    @Test
    fun `membership contains live sessions only`() {
        val first = backend.openSession(lease).id
        val second = backend.openSession(lease).id
        backend.join(first, "g")
        backend.join(first, "g")
        backend.join(second, "g")
        backend.members("g").assert().isEqualTo(listOf(first, second))
        backend.leave(second, "g")
        backend.leave(second, "g")
        backend.members("g").assert().isEqualTo(listOf(first))

        backend.acquire(first, setOf(name))
        backend.closeSession(first)
        backend.closeSession(first)
        backend.members("g").assert().isEmpty()
        hints.assert().contains(BackendHint.MembersChanged("g"), BackendHint.Released(name))

        val third = backend.openSession(lease).id
        backend.join(third, "g")
        clock.advance(lease)
        backend.members("g").assert().isEmpty()
        backend.members("none").assert().isEmpty()
    }

    @Test
    fun `unsubscribed listeners get no more hints`() {
        val received = ArrayList<BackendHint>()
        val subscription = backend.subscribe { received += it }
        subscription.close()
        val session = backend.openSession(lease).id
        backend.join(session, "g")
        received.assert().isEmpty()
    }

    @Test
    fun `session ids are never reused`() {
        val fixed = InMemoryLeaseBackend(clock) { SessionId("same") }
        fixed.openSession(lease)
        assertThrows<IllegalStateException> { fixed.openSession(lease) }
        InMemoryLeaseBackend().openSession(lease).id.value.length.assert().isEqualTo(32)
    }
}
