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
import me.ahoo.simba.RevokeReason
import me.ahoo.simba.Schedule
import me.ahoo.simba.Term
import me.ahoo.simba.TermExpiredException
import me.ahoo.simba.event.ElectionKind
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.WorkOutcome
import me.ahoo.simba.spi.Ticker
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException

/**
 * What term-bound work left: the [event] published together with the exit (so an observer of it finds the exit
 * already handled), and a [fatal] error that ends the term's work for good.
 */
internal class WorkResult(val event: SimbaEvent?, val fatal: VirtualMachineError? = null)

/** The body of term-bound work; [electedAt] is when the term was handed to the work (ticker nanoseconds). */
internal fun interface TermBody {
    fun run(term: EngineTerm, electedAt: Long): WorkResult
}

/**
 * Runs [body] once per term on a new task of [executor] (ADR 0010 contract 4): the task's thread is interrupted
 * when the term is revoked, and its exit is reported to the engine as [Event.WorkExited] so that a stop or step-down
 * can release the lease (contract 3). The election must be started with work.
 *
 * Failures of the work never affect the election, with one exception: a [VirtualMachineError] (out of memory, stack
 * overflow, an internal JVM error) means this node cannot do the work reliably, so the election steps down for
 * [fatalCooldown] nanoseconds and the error is rethrown to the work thread's uncaught-exception handler.
 */
internal class WorkListener(
    private val shell: EngineShell,
    private val executor: Executor,
    private val ticker: Ticker,
    private val fatalCooldown: Long,
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
            var result = WorkResult(null)
            try {
                if (started) {
                    working.set(true)
                    result = body.run(term, electedAt)
                }
            } finally {
                working.remove()
                // Clear a late interrupt so that a pooled thread does not carry it into its next task.
                synchronized(lock) {
                    thread = null
                    Thread.interrupted()
                }
                shell.workExited(term.name, term.id, result.event)
            }
            result.fatal?.let { fatal ->
                log.error(fatal) { "Work of [${term.name}] term [${term.token}] hit a fatal error; stepping down." }
                if (term.revokeReason == null) {
                    shell.stepDown(term.name, fatalCooldown)
                }
                throw fatal
            }
        }
    }
}

/**
 * Runs [work] once and returns its [SimbaEvent.WorkCompleted]: anything it throws is logged and reported, never
 * rethrown; a [VirtualMachineError] is also handed back as fatal.
 */
internal fun runReported(ticker: Ticker, term: EngineTerm, work: LeaderWork, kind: ElectionKind): WorkResult {
    val begin = ticker.nanoTime()
    val error = attempt(term, work)
    return WorkResult(completed(term, error, ticker.nanoTime() - begin, kind), error as? VirtualMachineError)
}

/** Runs [work] once; what it threw, if anything. */
@Suppress("TooGenericExceptionCaught")
private fun attempt(term: EngineTerm, work: LeaderWork): Throwable? =
    try {
        work.run(term)
        null
    } catch (error: Throwable) {
        error
    }

private val workLog = KotlinLogging.logger {}

internal fun completed(term: EngineTerm, error: Throwable?, nanos: Long, kind: ElectionKind): SimbaEvent.WorkCompleted {
    val outcome = when {
        error == null -> WorkOutcome.COMPLETED
        error is VirtualMachineError -> WorkOutcome.FAILED
        term.revokeReason != null || error is TermExpiredException -> WorkOutcome.CANCELLED
        else -> WorkOutcome.FAILED
    }
    if (outcome == WorkOutcome.FAILED) {
        workLog.warn(error) { "Work of [${term.name}] term [${term.token}] failed." }
    } else if (error != null) {
        workLog.debug(error) { "Work of [${term.name}] term [${term.token}] ended with its term." }
    }
    return SimbaEvent.WorkCompleted(term.name, term.token, outcome, Duration.ofNanos(nanos), error, kind)
}

/**
 * The [schedule] loop of one term, on the term's work thread. The wake-up of the next run is armed before the run
 * is published, so an observer of [SimbaEvent.WorkCompleted] always finds the next timer in place. A run that throws
 * (an [Error] included) is reported as failed and the loop goes on; an interrupt ends the loop, and so does a
 * [VirtualMachineError], which is handed back as fatal.
 */
internal class ScheduleBody(
    private val shell: EngineShell,
    private val ticker: Ticker,
    private val schedule: Schedule,
    private val work: LeaderWork,
    private val kind: ElectionKind,
) : TermBody {
    override fun run(term: EngineTerm, electedAt: Long): WorkResult {
        var at = electedAt + schedule.initialDelay.toNanos()
        var wake = Wake(at)
        try {
            while (await(term, wake.future)) {
                try {
                    term.ensureValid()
                } catch (error: TermExpiredException) {
                    workLog.debug(error) { "Schedule of [${term.name}] term [${term.token}] ends with its term." }
                    return WorkResult(null)
                }
                val begin = ticker.nanoTime()
                val error = attempt(term, work)
                val end = ticker.nanoTime()
                at = schedule.next(at, end)
                wake = Wake(at)
                shell.publish(completed(term, error, end - begin, kind))
                if (error is VirtualMachineError) {
                    return WorkResult(null, error)
                }
                if (error is InterruptedException || Thread.currentThread().isInterrupted) {
                    return WorkResult(null)
                }
            }
            return WorkResult(null)
        } finally {
            // The loop ended with its term: drop the next wake-up instead of leaving it on the loop.
            wake.timer.cancel()
        }
    }

    /** The wake-up at [at]: complete at once when due, otherwise a timer that can be cancelled. */
    private inner class Wake(at: Long) {
        val future = CompletableFuture<Void>()
        val timer: Cancellable = if (at - ticker.nanoTime() <= 0) {
            future.complete(null)
            Cancellable.NONE
        } else {
            shell.schedule(at) { future.complete(null) }
        }
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
