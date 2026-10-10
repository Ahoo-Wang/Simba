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
package me.ahoo.simba.spring.boot.starter.endpoint

import io.mockk.every
import io.mockk.mockk
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexRetrievalService.Status
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class SimbaServiceTrackerTest {
    private fun service(mutex: String, contenderId: String, status: Status): MutexContendService {
        return mockk {
            every { this@mockk.mutex } returns mutex
            every { this@mockk.contenderId } returns contenderId
            every { this@mockk.status } returns status
        }
    }

    @Test
    fun `lists running services ordered by mutex and contender`() {
        val tracker = SimbaServiceTracker()
        val b = service("b", "1", Status.RUNNING)
        val a2 = service("a", "2", Status.RUNNING)
        val a1 = service("a", "1", Status.STARTING)
        listOf(b, a2, a1).forEach(tracker::onStarted)

        tracker.services().assert().containsExactly(a1, a2, b)

        tracker.onStopped(a2)
        tracker.services().assert().containsExactly(a1, b)
    }

    @Test
    fun `a stop reported before its start does not leave a stale entry`() {
        val tracker = SimbaServiceTracker()
        val raced = service("raced", "1", Status.INITIAL)
        tracker.onStopped(raced)
        tracker.onStarted(raced)

        tracker.services().assert().isEmpty()
    }
}
