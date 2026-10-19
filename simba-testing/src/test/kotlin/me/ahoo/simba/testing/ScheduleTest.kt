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

import me.ahoo.simba.Schedule
import me.ahoo.simba.event.SimbaEvent
import me.ahoo.simba.event.WorkOutcome
import me.ahoo.simba.testing.VirtualSimba.Companion.WAIT
import me.ahoo.simba.testing.VirtualSimba.Companion.next
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** `Election.schedule` on virtual time: runs at the scheduled instants of its term only. */
class ScheduleTest {
    private val simba = VirtualSimba()
    private val runs = LinkedBlockingQueue<Long>()

    @AfterEach
    fun close() {
        simba.close()
    }

    private fun now() = simba.scheduler.nanoTime()

    /** Advances by [duration] and expects one run exactly then; waits until the next wake-up is armed. */
    private fun expectRunAfter(duration: Duration) {
        val at = now() + duration.toNanos()
        if (!duration.isZero) {
            simba.advance(duration.minusMillis(1))
            runs.poll().assert().isNull()
            simba.advance(Duration.ofMillis(1))
        }
        runs.next().assert().isEqualTo(at)
        simba.nextEvent<SimbaEvent.WorkCompleted>()
    }

    @Test
    fun `fixed delay runs after the initial delay, then a delay after each run ends`() {
        val client = simba.client()
        val blocker = CountDownLatch(1)
        var first = true
        client.election("report").schedule(Schedule.fixedDelay(Duration.ofSeconds(1), Duration.ofMillis(500))) {
            runs += now()
            if (first) {
                first = false
                blocker.await()
            }
        }
        val start = now()
        simba.advance(Duration.ofMillis(500))
        runs.next().assert().isEqualTo(start + 500 * MILLIS)
        simba.advance(Duration.ofMillis(1_500))
        blocker.countDown()
        simba.nextEvent<SimbaEvent.WorkCompleted>()
        // The first run ended at 2s, so the next one starts at 3s.
        expectRunAfter(Duration.ofSeconds(1))
        runs.isEmpty().assert().isTrue()
        (now() - start).assert().isEqualTo(3 * SECOND)
    }

    @Test
    fun `fixed rate keeps the grid and skips starts missed by an overrun`() {
        val client = simba.client()
        val blocker = CountDownLatch(1)
        var first = true
        client.election("rate").schedule(Schedule.fixedRate(Duration.ofSeconds(1))) {
            runs += now()
            if (first) {
                first = false
                blocker.await()
            }
        }
        val start = now()
        simba.advance(Duration.ZERO)
        runs.next().assert().isEqualTo(start)
        simba.advance(Duration.ofMillis(2_500))
        blocker.countDown()
        simba.nextEvent<SimbaEvent.WorkCompleted>()
        expectRunAfter(Duration.ofMillis(500))
        (now() - start).assert().isEqualTo(3 * SECOND)
        expectRunAfter(Duration.ofSeconds(1))
    }

    @Test
    fun `a failed run is reported and the loop goes on`() {
        val client = simba.client()
        var calls = 0
        client.election("flaky").schedule(Schedule.fixedDelay(Duration.ofSeconds(1))) {
            runs += now()
            check(++calls > 1) { "first run fails" }
        }
        simba.advance(Duration.ZERO)
        runs.next()
        simba.nextEvent<SimbaEvent.WorkCompleted>().outcome.assert().isEqualTo(WorkOutcome.FAILED)
        simba.advance(Duration.ofSeconds(1))
        runs.next()
        simba.nextEvent<SimbaEvent.WorkCompleted>().outcome.assert().isEqualTo(WorkOutcome.COMPLETED)
    }

    @Test
    fun `the loop ends with its term and a new term starts a new loop`() {
        val client = simba.client()
        val tokens = LinkedBlockingQueue<Long>()
        val election = client.election("terms")
        election.schedule(Schedule.fixedDelay(Duration.ofSeconds(10))) { term -> tokens += term.token }
        simba.advance(Duration.ZERO)
        tokens.next().assert().isEqualTo(1L)
        simba.nextEvent<SimbaEvent.WorkCompleted>()

        // The waiting loop exits at once on step-down: no handover timeout is needed.
        election.stepDown(Duration.ofSeconds(1)).toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        simba.advance(Duration.ofSeconds(1))
        tokens.next().assert().isEqualTo(2L)
        election.stop().toCompletableFuture().get(WAIT, TimeUnit.SECONDS)
        simba.advance(Duration.ofSeconds(20))
        tokens.isEmpty().assert().isTrue()
    }

    companion object {
        const val MILLIS = 1_000_000L
        const val SECOND = 1_000 * MILLIS
    }
}
