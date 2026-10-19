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

package me.ahoo.simba

import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SimbaClientBoundedTest {
    /** Grants every lease; enough to drive the shell without simba-testing. */
    private class GrantingBackend : LeaseBackend {
        override fun openSession(lease: Duration, metadata: Map<String, String>) = SessionGrant(SessionId("s"), lease)

        override fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>) =
            KeepAliveResult.Live(lease, emptyMap())

        override fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult> =
            names.associateWith { AcquireResult.Granted(1) }

        override fun release(session: SessionId, leases: Map<LeaseName, Long>) = Unit

        override fun closeSession(session: SessionId) = Unit

        override fun join(session: SessionId, group: String) = Unit

        override fun leave(session: SessionId, group: String) = Unit

        override fun members(group: String): List<SessionId> = emptyList()
    }

    @Test
    fun `stopped one-off elections leave no dispatcher behind`() {
        SimbaClient.builder().backend(GrantingBackend()).build().use { client ->
            val elected = LinkedBlockingQueue<Term>()
            val listener = object : LeadershipListener {
                override fun onElected(term: Term) {
                    elected += term
                }

                override fun onRevoked(term: Term, reason: RevokeReason) = Unit
            }
            repeat(ONE_OFF_NAMES) { index ->
                val election = client.election("invoice-$index")
                election.start(listener)
                elected.poll(WAIT, TimeUnit.SECONDS).assert().isNotNull()
                election.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
            }
            client.election("kept").start(listener)
            elected.poll(WAIT, TimeUnit.SECONDS).assert().isNotNull()

            client.dispatcherCount().assert().isEqualTo(1)
        }
    }

    companion object {
        const val ONE_OFF_NAMES = 200
        const val WAIT = 5L
    }
}
