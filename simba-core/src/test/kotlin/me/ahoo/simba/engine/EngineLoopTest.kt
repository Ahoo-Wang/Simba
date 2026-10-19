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

package me.ahoo.simba.engine

import me.ahoo.simba.SimbaConfig
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.KeepAliveResult
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionGrant
import me.ahoo.simba.spi.SessionId
import me.ahoo.simba.spi.Ticker
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The shell's own event loop and timers (no virtual scheduler): posts racing or following its shutdown. */
class EngineLoopTest {
    private object Idle : LeaseBackend {
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

    private fun shell() = EngineShell(
        backend = Idle,
        config = EngineConfig.of(SimbaConfig()),
        ticker = Ticker.SYSTEM,
        ioExecutor = Runnable::run,
        callbackExecutor = Runnable::run,
        eventListeners = emptyList(),
        seed = 1,
    ).also { it.boot() }

    @Test
    fun `a task the executor cancels instead of rejecting counts as not queued`() {
        val terminating = object : ScheduledThreadPoolExecutor(1) {
            override fun schedule(command: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> =
                super.schedule(command, delay, unit).also { it.cancel(false) }

            override fun <V> schedule(callable: Callable<V>, delay: Long, unit: TimeUnit): ScheduledFuture<V> =
                super.schedule(callable, delay, unit)
        }
        terminating.enqueue({}, 0).assert().isNull()
        terminating.shutdown()
        ScheduledThreadPoolExecutor(1).apply { shutdown() }.enqueue({}, 0).assert().isNull()
        ScheduledThreadPoolExecutor(1).run {
            enqueue({}, 0).assert().isNotNull()
            shutdown()
        }
    }

    @Test
    fun `loop timers run on time, and at once once the loop is closed`() {
        val shell = shell()
        val fired = CompletableFuture<Void>()
        val cancelled = CompletableFuture<Void>()
        shell.schedule(System.nanoTime() + Duration.ofMillis(1).toNanos()) { fired.complete(null) }
        shell.schedule(System.nanoTime() + Duration.ofHours(1).toNanos()) { cancelled.complete(null) }.cancel()
        fired.get(WAIT, TimeUnit.SECONDS)

        shell.close().get(WAIT, TimeUnit.SECONDS)
        val late = CompletableFuture<Void>()
        shell.schedule(System.nanoTime() + Duration.ofHours(1).toNanos()) { late.complete(null) }
        late.isDone.assert().isTrue()
        cancelled.isDone.assert().isFalse()
        shell.idle().get(WAIT, TimeUnit.SECONDS)
        shell.stop(LeaseName("closed")).get(WAIT, TimeUnit.SECONDS)
        shell.footprint().get(WAIT, TimeUnit.SECONDS).elections.assert().isEqualTo(0)
    }

    private companion object {
        const val WAIT = 5L
    }
}
