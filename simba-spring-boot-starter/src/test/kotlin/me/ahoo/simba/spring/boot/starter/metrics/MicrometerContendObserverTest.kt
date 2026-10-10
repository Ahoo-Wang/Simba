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
package me.ahoo.simba.spring.boot.starter.metrics

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import me.ahoo.simba.core.ContendOutcome
import me.ahoo.simba.core.WorkOutcome
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class MicrometerContendObserverTest {
    private val registry = SimpleMeterRegistry()
    private val observer = MicrometerContendObserver(registry)

    private fun owner(mutex: String) = registry.get(MicrometerContendObserver.OWNER).tag("mutex", mutex).gauge().value()

    @Test
    fun `owner gauge follows acquisition and release`() {
        observer.onContend("m", false, 1_000, ContendOutcome.OTHER)
        owner("m").assert().isEqualTo(0.0)

        observer.onAcquired("m")
        owner("m").assert().isEqualTo(1.0)

        observer.onReleased("m")
        observer.onReleased("m")
        owner("m").assert().isEqualTo(0.0)
        registry.get(MicrometerContendObserver.OWNERSHIP_CHANGES).tags("mutex", "m", "change", "acquired")
            .counter().count().assert().isEqualTo(1.0)
        registry.get(MicrometerContendObserver.OWNERSHIP_CHANGES).tags("mutex", "m", "change", "released")
            .counter().count().assert().isEqualTo(2.0)
    }

    @Test
    fun `contention is timed by operation and outcome`() {
        observer.onContend("m", false, 2_000_000, ContendOutcome.OWNER)
        observer.onContend("m", true, 4_000_000, ContendOutcome.FAILED)

        val acquire = registry.get(MicrometerContendObserver.CONTEND)
            .tags("mutex", "m", "operation", "acquire", "outcome", "owner").timer()
        acquire.count().assert().isEqualTo(1)
        acquire.totalTime(TimeUnit.MILLISECONDS).assert().isEqualTo(2.0)
        registry.get(MicrometerContendObserver.CONTEND)
            .tags("mutex", "m", "operation", "renew", "outcome", "failed").timer().count().assert().isEqualTo(1)
    }

    @Test
    fun `lease expiry and work are recorded per mutex`() {
        observer.onLeaseExpired("m")
        observer.onWork("job", 3_000_000, WorkOutcome.INTERRUPTED)

        registry.get(MicrometerContendObserver.LEASE_EXPIRED).tag("mutex", "m").counter().count().assert()
            .isEqualTo(1.0)
        registry.get(MicrometerContendObserver.WORK).tags("mutex", "job", "outcome", "interrupted").timer()
            .count().assert().isEqualTo(1)
    }
}
