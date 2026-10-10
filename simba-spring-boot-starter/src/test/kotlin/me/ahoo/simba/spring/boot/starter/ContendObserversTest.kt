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
package me.ahoo.simba.spring.boot.starter

import io.mockk.mockk
import me.ahoo.simba.core.ContendObserver
import me.ahoo.simba.core.ContendOutcome
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.WorkOutcome
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.DefaultListableBeanFactory

class ContendObserversTest {
    private class Recording(private val name: String, private val events: MutableList<String>) : ContendObserver {
        override fun onStarted(service: MutexContendService) {
            events += "$name:started"
        }

        override fun onStopped(service: MutexContendService) {
            events += "$name:stopped"
        }

        override fun onContend(mutex: String, renew: Boolean, durationNanos: Long, outcome: ContendOutcome) {
            events += "$name:contend"
        }

        override fun onAcquired(mutex: String) {
            events += "$name:acquired"
        }

        override fun onReleased(mutex: String) {
            events += "$name:released"
            error("$name failed")
        }

        override fun onLeaseExpired(mutex: String) {
            events += "$name:expired"
        }

        override fun onWork(mutex: String, durationNanos: Long, outcome: WorkOutcome) {
            events += "$name:work"
        }
    }

    private fun observersOf(vararg observers: ContendObserver): ContendObserver {
        val beanFactory = DefaultListableBeanFactory()
        observers.forEachIndexed { index, observer -> beanFactory.registerSingleton("observer$index", observer) }
        return ContendObservers.of(beanFactory.getBeanProvider(ContendObserver::class.java))
    }

    @Test
    fun `no observer bean means no-op and a single bean is used as is`() {
        observersOf().assert().isSameAs(ContendObserver.NOOP)
        val single = Recording("a", mutableListOf())
        observersOf(single).assert().isSameAs(single)
    }

    @Test
    fun `several beans all receive every event even when one fails`() {
        val events = mutableListOf<String>()
        val composite = observersOf(Recording("a", events), Recording("b", events))

        val service = mockk<MutexContendService>()
        composite.onStarted(service)
        composite.onContend("m", false, 1, ContendOutcome.OWNER)
        composite.onAcquired("m")
        composite.onLeaseExpired("m")
        composite.onWork("m", 1, WorkOutcome.SUCCESS)
        composite.onStopped(service)
        val failure = assertThrows<IllegalStateException> { composite.onReleased("m") }

        events.assert().containsExactly(
            "a:started", "b:started",
            "a:contend", "b:contend",
            "a:acquired", "b:acquired",
            "a:expired", "b:expired",
            "a:work", "b:work",
            "a:stopped", "b:stopped",
            "a:released", "b:released"
        )
        failure.message.assert().isEqualTo("a failed")
        failure.suppressed.single().message.assert().isEqualTo("b failed")
    }
}
