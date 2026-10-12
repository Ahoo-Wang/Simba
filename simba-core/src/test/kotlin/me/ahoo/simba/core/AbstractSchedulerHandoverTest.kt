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
package me.ahoo.simba.core

import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AbstractSchedulerHandoverTest {
    private val events = ConcurrentLinkedQueue<String>()
    private val started = CountDownLatch(1)
    private val factory = CapturingFactory()
    private val scheduler = object : AbstractScheduler("m", factory) {
        override val config: ScheduleConfig = ScheduleConfig.delay(Duration.ZERO, Duration.ofHours(1))
        override val worker: String = "handover-worker"
        override fun work() {
            started.countDown()
            try {
                Thread.sleep(TimeUnit.MINUTES.toMillis(1))
            } catch (_: InterruptedException) {
                events += "work ended"
            }
        }
    }

    private fun lead() {
        val service = factory.service!!
        service.calls = events
        scheduler.start()
        service.publishOwner(MutexOwner(service.contenderId)).join()
        started.await(2, TimeUnit.SECONDS).assert().isTrue()
    }

    @Test
    fun `stop releases the mutex only after the running work returned`() {
        lead()

        scheduler.stop()

        events.toList().assert().containsExactly("work ended", "stopContend")
    }

    @Test
    fun `relinquish stops the work before releasing`() {
        lead()

        scheduler.relinquish(Duration.ofSeconds(1))

        events.toList().assert().containsExactly("work ended", "relinquish:1000")
        scheduler.running.assert().isTrue()
        scheduler.stop()
    }

    private class CapturingFactory : MutexContendServiceFactory {
        var service: FakeMutexContendService? = null

        override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
            return FakeMutexContendService(mutexContender).also { service = it }
        }
    }
}
