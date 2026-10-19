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

import me.ahoo.simba.engine.EngineConfig
import me.ahoo.simba.engine.EngineShell
import me.ahoo.simba.engine.InternalSimbaApi
import me.ahoo.simba.engine.VirtualThreads
import me.ahoo.simba.event.SimbaEventListener
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.Ticker
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Simba 5 client (ADR 0010): one per process, one backend session, every election attached to that session.
 *
 * Named `SimbaClient` while the 4.x `Simba` constants object still exists; it becomes `Simba` once 4.x is removed.
 * [close] closes the session, so the backend frees every lease at once.
 */
@OptIn(InternalSimbaApi::class)
class SimbaClient private constructor(builder: Builder) : AutoCloseable {
    private val ownedExecutors = ArrayList<ExecutorService>()
    private val shell: EngineShell
    private val elections = ConcurrentHashMap<LeaseName, Election>()
    private val closing = AtomicBoolean(false)
    private val closed = CompletableFuture<Void>()

    init {
        val backend = requireNotNull(builder.backend) { "backend is required." }
        shell = EngineShell(
            backend = backend,
            config = EngineConfig.of(builder.config),
            ticker = builder.ticker,
            ioExecutor = builder.ioExecutor ?: VirtualThreads.newExecutor("simba-io").also(ownedExecutors::add),
            callbackExecutor = builder.callbackExecutor
                ?: VirtualThreads.newExecutor("simba-callback").also(ownedExecutors::add),
            eventListeners = builder.listeners.toList(),
            seed = builder.seed ?: ThreadLocalRandom.current().nextLong(),
        )
        shell.boot()
    }

    /** The election for [name]; the same object for the same name. */
    fun election(name: String): Election {
        check(!closing.get()) { "SimbaClient is closed." }
        val leaseName = LeaseName(name)
        return elections.computeIfAbsent(leaseName) { ShellElection(it) }
    }

    /** Revokes every term, closes the session and stops the engine; completes after the last callback ran. */
    fun closeAsync(): CompletionStage<Void> {
        if (closing.compareAndSet(false, true)) {
            shell.close().whenComplete { _, error ->
                ownedExecutors.forEach(ExecutorService::shutdown)
                if (error == null) closed.complete(null) else closed.completeExceptionally(error)
            }
        }
        return closed.minimalCompletionStage()
    }

    /** [closeAsync] and wait, except inside a leadership callback, where waiting would wait for itself. */
    override fun close() {
        val stage = closeAsync()
        if (!EngineShell.isDispatching()) {
            stage.toCompletableFuture().join()
        }
    }

    private inner class ShellElection(override val name: LeaseName) : Election {
        private val started = AtomicBoolean(false)

        override fun start(listener: LeadershipListener) {
            check(!closing.get()) { "SimbaClient is closed." }
            check(started.compareAndSet(false, true)) { "Election [$name] is already started." }
            shell.start(name, listener)
        }

        override fun stop(): CompletionStage<Void> {
            if (!started.compareAndSet(true, false)) {
                return CompletableFuture.completedStage(null)
            }
            return shell.stop(name).minimalCompletionStage()
        }

        override fun stepDown(cooldown: Duration): CompletionStage<Void> {
            require(!cooldown.isNegative) { "cooldown must not be negative." }
            return shell.stepDown(name, cooldown.toNanos()).minimalCompletionStage()
        }
    }

    class Builder internal constructor() {
        internal var backend: LeaseBackend? = null
        internal var config: SimbaConfig = SimbaConfig()
        internal var ticker: Ticker = Ticker.SYSTEM
        internal var ioExecutor: Executor? = null
        internal var callbackExecutor: Executor? = null
        internal val listeners = ArrayList<SimbaEventListener>()
        internal var seed: Long? = null

        fun backend(backend: LeaseBackend) = apply { this.backend = backend }

        fun config(config: SimbaConfig) = apply { this.config = config }

        /** Runs backend calls; default one virtual thread per call (Java 21+) or a cached daemon pool. */
        fun ioExecutor(executor: Executor) = apply { ioExecutor = executor }

        /** Runs leadership callbacks, serially per election; default as [ioExecutor]. */
        fun callbackExecutor(executor: Executor) = apply { callbackExecutor = executor }

        fun listener(listener: SimbaEventListener) = apply { listeners += listener }

        /** Must advance at the rate of [System.nanoTime]: the event loop schedules its timers with it. */
        fun ticker(ticker: Ticker) = apply { this.ticker = ticker }

        /** Seed of the retry jitter. */
        fun seed(seed: Long) = apply { this.seed = seed }

        fun build(): SimbaClient = SimbaClient(this)
    }

    companion object {
        @JvmStatic
        fun builder(): Builder = Builder()
    }
}
