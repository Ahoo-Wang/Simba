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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.LeadershipListener
import me.ahoo.simba.event.SimbaEventListener
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spi.LeaseName
import me.ahoo.simba.spi.Ticker
import me.ahoo.simba.util.SequentialExecutor
import me.ahoo.simba.util.Threads
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs [step] on a single event-loop thread and executes its effects: backend calls on [ioExecutor] with a timeout
 * of one renew interval, timers on the loop, and callbacks on one serial dispatcher per election over
 * [callbackExecutor]. The engine state is only touched by the loop thread.
 */
@OptIn(InternalSimbaApi::class)
internal class EngineShell(
    private val backend: LeaseBackend,
    private val config: EngineConfig,
    private val ticker: Ticker,
    private val ioExecutor: Executor,
    private val callbackExecutor: Executor,
    private val eventListeners: List<SimbaEventListener>,
    seed: Long,
) {
    companion object {
        private val log = KotlinLogging.logger {}
        private val dispatching = ThreadLocal<Boolean>()

        /** `true` on a thread currently running a leadership callback. */
        fun isDispatching(): Boolean = dispatching.get() == true
    }

    private val loop = ScheduledThreadPoolExecutor(1, Threads.defaultFactory("simba-engine", daemon = true)).apply {
        removeOnCancelPolicy = true
        executeExistingDelayedTasksAfterShutdownPolicy = false
    }
    private var state = EngineState.initial(config, seed)
    private val terms = TermRegistry(ticker)
    private val dispatchers = HashMap<LeaseName, Dispatcher>()
    private val hintSubscription = backend.subscribe { hint -> post(Event.Hint(hint)) }

    /**
     * [active] while the election is started; [starts] counts starts. A stopped dispatcher is dropped once the
     * callbacks of its last stop ran, unless it was started again since.
     */
    private class Dispatcher(val executor: SequentialExecutor) {
        var listener: LeadershipListener? = null
        var active = false
        var starts = 0L
    }

    fun boot() {
        post(Event.Boot)
    }

    fun start(name: LeaseName, listener: LeadershipListener) {
        onLoop {
            val dispatcher = dispatcher(name)
            dispatcher.listener = listener
            dispatcher.active = true
            dispatcher.starts++
            handle(Event.Start(name))
        }
    }

    fun stop(name: LeaseName): CompletableFuture<Void> {
        val done = CompletableFuture<Void>()
        val stopped = CompletableFuture<Void>()
        val accepted = onLoop {
            val dispatcher = dispatchers[name]
            dispatcher?.active = false
            val starts = dispatcher?.starts
            // `done` completes on the dispatcher after the stop's callbacks; then drop the dispatcher unless it was
            // started again since, and only then report the stop.
            done.whenComplete { _, _ ->
                val posted = onLoop {
                    val current = dispatchers[name]
                    if (current != null && !current.active && current.starts == starts) {
                        dispatchers.remove(name)
                    }
                    stopped.complete(null)
                }
                if (!posted) {
                    stopped.complete(null)
                }
            }
            handle(Event.Stop(name, done))
        }
        if (!accepted) {
            stopped.complete(null)
        }
        return stopped
    }

    /** Dispatchers kept, one per started election or stop still draining; for tests. */
    internal fun dispatcherCount(): CompletableFuture<Int> =
        CompletableFuture<Int>().also { count -> onLoop { count.complete(dispatchers.size) } }

    fun stepDown(name: LeaseName, cooldownNanos: Long): CompletableFuture<Void> =
        CompletableFuture<Void>().also { post(Event.StepDown(name, cooldownNanos, it)) }

    /** Revokes every term, closes the session, waits for pending callbacks, then stops the loop. */
    fun close(): CompletableFuture<Void> {
        val closed = CompletableFuture<Void>()
        post(Event.Close(closed))
        return closed.thenCompose { drainDispatchers() }
            .whenComplete { _, _ ->
                hintSubscription.close()
                loop.shutdown()
            }
    }

    private fun drainDispatchers(): CompletableFuture<Void> {
        val drained = CompletableFuture<List<SequentialExecutor>>()
        onLoop { drained.complete(dispatchers.values.map { it.executor }) }
        return drained.thenCompose { executors ->
            val all = executors.map { executor ->
                val drainedOne = CompletableFuture<Void>()
                executor.execute { drainedOne.complete(null) }
                drainedOne
            }
            CompletableFuture.allOf(*all.toTypedArray())
        }
    }

    private fun post(event: Event) {
        onLoop { handle(event) }
    }

    /** Runs [action] on the loop; `false` when the loop is already shut down. */
    private fun onLoop(action: () -> Unit): Boolean {
        try {
            loop.execute {
                try {
                    action()
                } catch (error: Throwable) {
                    log.error(error) { "Simba engine failed to handle an event." }
                }
            }
        } catch (error: RejectedExecutionException) {
            log.debug(error) { "Simba engine is closed; event dropped." }
            return false
        }
        return true
    }

    private fun handle(event: Event) {
        val (next, effects) = step(state, event, ticker.nanoTime())
        state = next
        effects.forEach(::execute)
    }

    private fun execute(effect: Effect) {
        when (effect) {
            is Effect.Call -> call(effect)
            is Effect.Arm -> schedule(effect.at - ticker.nanoTime()) { handle(Event.Timer(effect.id)) }
            is Effect.Deadline -> terms.deadline(effect)
            is Effect.Elected -> {
                val term = terms.elected(effect)
                dispatch(effect.name) { it.onElected(term) }
            }

            is Effect.Revoked -> {
                val term = terms.revoked(effect)
                dispatch(effect.name) { it.onRevoked(term, effect.reason) }
            }

            is Effect.Complete -> {
                val dispatcher = effect.name?.let { dispatchers[it] }
                if (dispatcher == null) {
                    effect.done.complete(null)
                } else {
                    dispatcher.executor.execute { effect.done.complete(null) }
                }
            }

            is Effect.Publish -> eventListeners.forEach { listener ->
                try {
                    listener.onEvent(effect.event)
                } catch (error: Throwable) {
                    log.warn(error) { "SimbaEventListener failed on ${effect.event}." }
                }
            }
        }
    }

    private fun call(effect: Effect.Call) {
        val replied = AtomicBoolean(false)
        fun reply(result: BackendResult) {
            if (replied.compareAndSet(false, true)) {
                post(Event.Reply(effect.requestId, result))
            }
        }
        schedule(config.renewInterval) { reply(BackendResult.Failed(TimeoutException("${effect.request} timed out."))) }
        try {
            ioExecutor.execute { reply(backend.execute(effect.request)) }
        } catch (error: RejectedExecutionException) {
            reply(BackendResult.Failed(error))
        }
    }

    private fun schedule(delayNanos: Long, action: () -> Unit) {
        try {
            loop.schedule(action, delayNanos, TimeUnit.NANOSECONDS)
        } catch (error: RejectedExecutionException) {
            log.debug(error) { "Simba engine is closed; timer dropped." }
        }
    }

    private fun dispatcher(name: LeaseName): Dispatcher =
        dispatchers.getOrPut(name) { Dispatcher(SequentialExecutor(callbackExecutor)) }

    /** Captures the listener now, on the loop, so a restart with another listener never receives older callbacks. */
    private fun dispatch(name: LeaseName, callback: (LeadershipListener) -> Unit) {
        val dispatcher = dispatcher(name)
        val listener = dispatcher.listener ?: return
        dispatcher.executor.execute {
            dispatching.set(true)
            try {
                callback(listener)
            } catch (error: Throwable) {
                log.warn(error) { "Leadership callback of [$name] failed." }
            } finally {
                dispatching.remove()
            }
        }
    }
}
