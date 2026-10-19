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

package me.ahoo.simba.spi

import me.ahoo.simba.engine.BackendRequest
import me.ahoo.simba.engine.BackendResult
import me.ahoo.simba.engine.execute
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration

class SpiTest {
    @Test
    fun `lease names are validated`() {
        LeaseName("a.B_c:9-z").toString().assert().isEqualTo("a.B_c:9-z")
        (LeaseName("a") < LeaseName("b")).assert().isTrue()
        assertThrows<IllegalArgumentException> { LeaseName("") }
        assertThrows<IllegalArgumentException> { LeaseName("x".repeat(LeaseName.MAX_LENGTH + 1)) }
        assertThrows<IllegalArgumentException> { LeaseName("a/b") }
        assertThrows<IllegalArgumentException> { LeaseName("{a}") }
    }

    @Test
    fun `session ids are random 128-bit hex`() {
        val id = SessionId.random()
        id.value.length.assert().isEqualTo(32)
        (id == SessionId.random()).assert().isFalse()
        id.toString().assert().isEqualTo(id.value)
        assertThrows<IllegalArgumentException> { SessionId("") }
    }

    @Test
    fun `defaults of a minimal backend`() {
        val backend = ThrowingBackend()
        backend.capabilities.assert().isEqualTo(Capabilities())
        backend.subscribe { }.close()
        backend.close()
        Ticker.SYSTEM.nanoTime()
    }

    @Test
    fun `execute turns backend exceptions into failures`() {
        val backend = ThrowingBackend()
        val session = SessionId("s")
        val name = LeaseName("n")
        listOf(
            BackendRequest.OpenSession(Duration.ofSeconds(1)),
            BackendRequest.KeepAlive(session, Duration.ofSeconds(1), setOf(name)),
            BackendRequest.Acquire(session, setOf(name)),
            BackendRequest.Release(session, mapOf(name to 1L)),
            BackendRequest.CloseSession(session),
        ).forEach { request ->
            (backend.execute(request) as BackendResult.Failed).cause.message.assert().isEqualTo("down")
        }
        SessionExpiredException(session).session.assert().isEqualTo(session)
    }

    private class ThrowingBackend : LeaseBackend {
        override fun openSession(lease: Duration, metadata: Map<String, String>): SessionGrant = error("down")

        override fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult = error(
            "down"
        )

        override fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult> = error("down")

        override fun release(session: SessionId, leases: Map<LeaseName, Long>) = error("down")

        override fun closeSession(session: SessionId) = error("down")

        override fun join(session: SessionId, group: String) = Unit

        override fun leave(session: SessionId, group: String) = Unit

        override fun members(group: String): List<SessionId> = emptyList()
    }
}
