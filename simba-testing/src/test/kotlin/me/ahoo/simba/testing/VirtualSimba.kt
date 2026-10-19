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

import me.ahoo.simba.Simba
import me.ahoo.simba.SimbaConfig
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.spi.AcquireResult
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.SessionId
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Threaded clients on virtual time: one [VirtualScheduler] for every client and the backend, direct I/O and
 * callbacks, real (virtual-thread) work. [advance] settles every engine before each timer; tests wait on queues for
 * what work threads do, never sleep.
 */
class VirtualSimba : AutoCloseable {
    val scheduler = VirtualScheduler()
    val backend = InMemoryLeaseBackend(scheduler)

    /** Names of every acquire call that reached the backend, in order. */
    val acquires = LinkedBlockingQueue<Set<LeaseName>>()
    private val recording = object : LeaseBackend by backend {
        override fun acquire(session: SessionId, names: Set<LeaseName>): Map<LeaseName, AcquireResult> {
            acquires += names
            return backend.acquire(session, names)
        }
    }
    val config = SimbaConfig(
        leaseDuration = Duration.ofSeconds(3),
        renewInterval = Duration.ofSeconds(1),
        margin = Duration.ofMillis(20),
        retryJitter = Duration.ZERO,
    )
    val events = LinkedBlockingQueue<SimbaEvent>()
    private val clients = ArrayList<Simba>()

    fun client(): Simba =
        Simba.builder()
            .backend(recording)
            .config(config)
            .scheduler(scheduler)
            .ioExecutor(Runnable::run)
            .callbackExecutor(Runnable::run)
            .listener { events += it }
            .seed(clients.size.toLong())
            .build()
            .also {
                clients += it
                settle()
            }

    /** Until two rounds over every client handle nothing new. */
    fun settle() {
        var last: List<Long>? = null
        while (true) {
            val handled = clients.map { it.awaitIdle() }
            if (handled == last) {
                return
            }
            last = handled
        }
    }

    fun advance(duration: Duration) {
        scheduler.advance(duration, ::settle)
    }

    inline fun <reified E : SimbaEvent> nextEvent(): E {
        while (true) {
            val event = checkNotNull(events.poll(WAIT, TimeUnit.SECONDS)) { "No ${E::class.simpleName} event." }
            if (event is E) {
                return event
            }
        }
    }

    override fun close() {
        clients.forEach { it.closeAsync() }
        clients.forEach {
            settle()
            it.closeAsync().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        }
    }

    companion object {
        const val WAIT = 5L

        fun <T> LinkedBlockingQueue<T>.next(): T = checkNotNull(poll(WAIT, TimeUnit.SECONDS)) { "timed out" }
    }
}
