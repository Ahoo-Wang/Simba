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

import me.ahoo.simba.spi.LeaseName
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/**
 * Leader election for one lease name on one client session.
 *
 * Contract:
 * - every [LeadershipListener.onElected] is followed by exactly one [LeadershipListener.onRevoked] for the same
 *   term, and callbacks of one election run one at a time, in order;
 * - the engine never waits for a callback, except through the futures returned by [stop] and [stepDown];
 * - after the future returned by [stop] completes, the listener receives no more callbacks.
 */
interface Election {
    val name: LeaseName

    /** Starts contending; throws [IllegalStateException] when already started. */
    fun start(listener: LeadershipListener)

    /**
     * Stops contending; completes after the last `onRevoked` ran. Completes at once when not started. On the event
     * loop (a callback run by a direct callback executor) chain on the stage: converting it with
     * `toCompletableFuture()` throws [IllegalStateException] while it is pending, since waiting would block the loop.
     * The same holds for every stage the client returns.
     */
    fun stop(): CompletionStage<Void>

    /**
     * Gives up the current term (if any) and does not contend for [cooldown] from now; the election stays started.
     * Like [stop], it waits for work bound to the term before releasing the lease.
     */
    fun stepDown(cooldown: Duration): CompletionStage<Void>

    /**
     * Starts the election and runs [work] once per term, each time on a new thread from [executor] (default: the
     * client's work executor, one virtual thread per term). The block only ever sees its own [Term].
     *
     * When the term is revoked, its thread is interrupted and [Term.revoked] completes. A [stop] or [stepDown]
     * releases the lease only after the block returned or the term's remaining time at the stop ran out, whichever
     * comes first (ADR 0010 contract 3); a term lost otherwise (deadline, take-over, session expiry) is not waited
     * for, so the block must check [Term.isValid] or the token. A block returning early keeps the term until it
     * ends. Exceptions are logged and published as [me.ahoo.simba.event.SimbaEvent.WorkCompleted]; they never
     * affect the election. Throws [IllegalStateException] when the election is already started.
     */
    fun runWhileLeader(executor: Executor?, work: LeaderWork): LeaderTask

    /** [runWhileLeader] on the client's work executor. */
    fun runWhileLeader(work: LeaderWork): LeaderTask = runWhileLeader(null, work)

    /**
     * Runs [work] on [schedule] while leader: one loop per term on one virtual thread of the client's work
     * executor (no thread pool per schedule), calling [Term.ensureValid] before every run. A failed run is logged and
     * published, and the loop goes on; the loop ends with its term.
     */
    fun schedule(schedule: Schedule, work: LeaderWork): LeaderTask
}

/** Work bound to one term; see [Election.runWhileLeader]. */
fun interface LeaderWork {
    @Throws(Exception::class)
    fun run(term: Term)
}

/** A started [Election] running work; stopping it stops the election. */
interface LeaderTask : AutoCloseable {
    val election: Election

    /** Stops the election: completes once the work of the current term exited (or timed out) and the lease is released. */
    fun stop(): CompletionStage<Void>

    /** [stop] and wait, except inside a callback or the work itself, where waiting would wait for itself. */
    override fun close()
}

/** Callbacks of an [Election]. Keep them short: they run on the election's serial dispatcher. */
interface LeadershipListener {
    fun onElected(term: Term)

    fun onRevoked(term: Term, reason: RevokeReason)
}
