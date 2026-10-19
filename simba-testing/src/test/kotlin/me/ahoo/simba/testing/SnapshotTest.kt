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

import me.ahoo.simba.LeadershipListener
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Simba
import me.ahoo.simba.SimbaConfig
import me.ahoo.simba.SimbaException
import me.ahoo.simba.Term
import me.ahoo.simba.event.ElectionStatus
import me.ahoo.simba.event.SessionStatus
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class SnapshotTest {
    private val scheduler = VirtualScheduler()
    private val backend = InMemoryLeaseBackend(scheduler)

    @Volatile
    private var unreachable = false
    private val flaky = object : LeaseBackend by backend {
        override fun openSession(lease: Duration, metadata: Map<String, String>): SessionGrant {
            if (unreachable) {
                throw SimbaException("unreachable")
            }
            return backend.openSession(lease, metadata)
        }

        override fun keepAlive(session: SessionId, lease: Duration, held: Set<LeaseName>): KeepAliveResult {
            if (unreachable) {
                throw SimbaException("unreachable")
            }
            return backend.keepAlive(session, lease, held)
        }
    }
    private val config = SimbaConfig.builder().leaseDuration(Duration.ofSeconds(3)).renewInterval(Duration.ofSeconds(1)).margin(Duration.ofMillis(20)).retryJitter(Duration.ZERO).build()
    private val simba = Simba.builder()
        .backend(flaky)
        .config(config)
        .scheduler(scheduler)
        .ioExecutor(Runnable::run)
        .callbackExecutor(Runnable::run)
        .seed(0)
        .build()

    private fun settle() {
        var last = -1L
        while (true) {
            val handled = simba.awaitIdle()
            if (handled == last) {
                return
            }
            last = handled
        }
    }

    private fun advance(duration: Duration) {
        scheduler.advance(duration, ::settle)
    }

    @AfterEach
    fun close() {
        unreachable = false
        simba.closeAsync()
        settle()
        simba.closeAsync().toCompletableFuture().get(VirtualSimba.WAIT, TimeUnit.SECONDS)
    }

    private object Ignore : LeadershipListener {
        override fun onElected(term: Term) = Unit

        override fun onRevoked(term: Term, reason: RevokeReason) = Unit
    }

    @Test
    fun `a live session reports every election with its token and remaining time`() {
        settle()
        simba.election("orders").start(Ignore)
        settle()

        val snapshot = simba.snapshot()
        snapshot.session.status.assert().isEqualTo(SessionStatus.LIVE)
        snapshot.session.id.assert().isNotNull()
        (snapshot.session.validFor > Duration.ZERO).assert().isTrue()
        snapshot.healthy.assert().isTrue()
        val election = snapshot.elections.single()
        election.name.assert().isEqualTo(LeaseName("orders"))
        election.status.assert().isEqualTo(ElectionStatus.LEADER)
        election.token.assert().isEqualTo(1L)
        (election.remaining > Duration.ZERO).assert().isTrue()
    }

    @Test
    fun `a session not live for longer than the lease duration is unhealthy`() {
        settle()
        unreachable = true
        advance(Duration.ofSeconds(1))
        simba.snapshot().session.status.assert().isEqualTo(SessionStatus.SUSPENDED)
        simba.snapshot().healthy.assert().isTrue()

        advance(Duration.ofSeconds(4))
        val snapshot = simba.snapshot()
        (snapshot.session.status == SessionStatus.LIVE).assert().isFalse()
        snapshot.session.notLiveFor.assert().isEqualTo(Duration.ofSeconds(4))
        snapshot.healthy.assert().isFalse()

        unreachable = false
        advance(Duration.ofSeconds(2))
        simba.snapshot().session.status.assert().isEqualTo(SessionStatus.LIVE)
        simba.snapshot().healthy.assert().isTrue()
    }

    @Test
    fun `a closed client reports a closed session`() {
        simba.closeAsync()
        settle()
        simba.closeAsync().toCompletableFuture().get(VirtualSimba.WAIT, TimeUnit.SECONDS)

        val snapshot = simba.snapshot()
        snapshot.session.status.assert().isEqualTo(SessionStatus.CLOSED)
        snapshot.elections.assert().isEmpty()
    }
}
