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
import me.ahoo.simba.LeaderWork
import me.ahoo.simba.LeadershipListener
import me.ahoo.simba.LeaseExpiredException
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Schedule
import me.ahoo.simba.Term
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.WorkOutcome
import me.ahoo.simba.spi.Ticker
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * The body of term-bound work; [electedAt] is when the term was handed to the work (ticker nanoseconds). The returned
 * event is published together with the exit, so an observer of it finds the exit already handled.
 */
internal fun interface TermBody {
    fun run(term: EngineTerm, electedAt: Long): SimbaEvent?
}

/**
 * Runs [body] once per term on a new task of [executor] (ADR 0010 contract 4): the task's thread is interrupted
 * when the term is revoked, and its exit is reported to the engine as [Event.WorkExited] so that a stop or step-down
 * can release the lease (contract 3). The election must be started with work.
 */
internal class WorkListener(
    private val shell: EngineShell,
    private val executor: Executor,
    private val ticker: Ticker,
    private val body: TermBody,
) : LeadershipListener {
    companion object {
        private val log = KotlinLogging.logger {}
        private val working = ThreadLocal<Boolean>()

        /** `true` on a thread currently running leader work. */
        fun isWorking(): Boolean = working.get() == true
    }

    override fun onElected(term: Term) {
        val engineTerm = term as EngineTerm
        val job = Job(engineTerm, ticker.nanoTime())
        term.revoked.whenComplete { _, _ -> job.cancel() }
        try {
            executor.execute(job)
        } catch (error: RejectedExecutionException) {
            log.warn(error) { "Work of [${term.name}] term [${term.token}] was rejected." }
            shell.workExited(term.name, engineTerm.id)
        }
    }

    override fun onRevoked(term: Term, reason: RevokeReason) = Unit

    private inner class Job(private val term: EngineTerm, private val electedAt: Long) : Runnable {
        private val lock = Any()
        private var thread: Thread? = null
        private var cancelled = false

        fun cancel() {
            synchronized(lock) {
                cancelled = true
                thread?.interrupt()
            }
        }

        override fun run() {
            val started = synchronized(lock) {
                if (!cancelled) {
                    thread = Thread.currentThread()
                }
                !cancelled
            }
            var event: SimbaEvent? = null
            try {
                if (started) {
                    working.set(true)
                    event = body.run(term, electedAt)
                }
            } finally {
                working.remove()
                // Clear a late interrupt so that a pooled thread does not carry it into its next task.
                synchronized(lock) {
                    thread = null
                    Thread.interrupted()
                }
                shell.workExited(term.name, term.id, event)
            }
        }
    }
}

/** Runs [work] once and returns its [SimbaEvent.WorkCompleted]; exceptions are logged, never rethrown. */
internal fun runReported(ticker: Ticker, term: EngineTerm, work: LeaderWork): SimbaEvent.WorkCompleted {
    val begin = ticker.nanoTime()
    val error = try {
        work.run(term)
        null
    } catch (error: Exception) {
        error
    }
    return completed(term, error, ticker.nanoTime() - begin)
}

private val workLog = KotlinLogging.logger {}

internal fun completed(term: EngineTerm, error: Exception?, nanos: Long): SimbaEvent.WorkCompleted {
    val outcome = when {
        error == null -> WorkOutcome.COMPLETED
        term.revokeReason != null || error is LeaseExpiredException -> WorkOutcome.CANCELLED
        else -> WorkOutcome.FAILED
    }
    if (outcome == WorkOutcome.FAILED) {
        workLog.warn(error) { "Work of [${term.name}] term [${term.token}] failed." }
    } else if (error != null) {
        workLog.debug(error) { "Work of [${term.name}] term [${term.token}] ended with its term." }
    }
    return SimbaEvent.WorkCompleted(term.name, term.token, outcome, Duration.ofNanos(nanos), error)
}

/**
 * The [schedule] loop of one term, on the term's work thread. The wake-up of the next run is armed before the run
 * is published, so an observer of [SimbaEvent.WorkCompleted] always finds the next timer in place.
 */
internal class ScheduleBody(
    private val shell: EngineShell,
    private val ticker: Ticker,
    private val schedule: Schedule,
    private val work: LeaderWork,
) : TermBody {
    override fun run(term: EngineTerm, electedAt: Long): SimbaEvent? {
        var at = electedAt + schedule.initialDelay.toNanos()
        var wake = wakeAt(at)
        while (await(term, wake)) {
            try {
                term.ensureValid()
            } catch (error: LeaseExpiredException) {
                workLog.debug(error) { "Schedule of [${term.name}] term [${term.token}] ends with its term." }
                return null
            }
            val begin = ticker.nanoTime()
            val error = try {
                work.run(term)
                null
            } catch (error: Exception) {
                error
            }
            val end = ticker.nanoTime()
            at = schedule.next(at, end)
            wake = wakeAt(at)
            shell.publish(completed(term, error, end - begin))
            if (error is InterruptedException || Thread.currentThread().isInterrupted) {
                return null
            }
        }
        return null
    }

    private fun wakeAt(at: Long): CompletableFuture<Void> {
        val wake = CompletableFuture<Void>()
        if (at - ticker.nanoTime() <= 0) {
            wake.complete(null)
        } else {
            shell.schedule(at) { wake.complete(null) }
        }
        return wake
    }

    /** Waits for [wake] or the end of the term; `false` once the term was revoked or the thread interrupted. */
    private fun await(term: EngineTerm, wake: CompletableFuture<Void>): Boolean {
        if (!wake.isDone) {
            try {
                CompletableFuture.anyOf(wake, term.revoked.toCompletableFuture()).get()
            } catch (_: InterruptedException) {
                return false
            }
        }
        return term.revokeReason == null
    }
}
