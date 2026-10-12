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

import me.ahoo.simba.locker.Locker
import me.ahoo.simba.locker.SimbaLocker
import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig
import me.ahoo.simba.schedule.ScheduleContext
import me.ahoo.simba.schedule.SimbaScheduler
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class LeaseRemainingTest {
    private val contender = FakeMutexContender("m", "c1")
    private val service = FakeMutexContendService(contender)

    @Test
    fun `remaining lease runs to the transition end of the observed owner`() {
        service.start()
        service.publishOwner(observedOwner("c1", ttlAt = 1_500, transitionAt = 2_000, observedAt = 1_000)).join()

        service.leaseRemaining.toMillis().assert().isBetween(990, 1_000)
        service.ensureLease(Duration.ofMillis(500))
        assertThrows<LeaseExpiredException> { service.ensureLease(Duration.ofMillis(1_000)) }
        service.stop()
    }

    @Test
    fun `remaining lease is zero without ownership or after the lease ended`() {
        service.start()
        service.leaseRemaining.assert().isEqualTo(Duration.ZERO)

        service.publishOwner(observedOwner("c2", ttlAt = 1_500, transitionAt = 2_000, observedAt = 1_000)).join()
        service.leaseRemaining.assert().isEqualTo(Duration.ZERO)

        service.publishOwner(observedOwner("c1", ttlAt = 500, transitionAt = 800, observedAt = 1_000)).join()
        service.leaseRemaining.assert().isEqualTo(Duration.ZERO)
        assertThrows<LeaseExpiredException> { service.ensureLease() }
        service.stop()
    }

    @Test
    fun `ownership without a time bound is unbounded`() {
        service.start()
        service.publishOwner(MutexOwner("c1")).join()

        service.leaseRemaining.assert().isEqualTo(MutexContendService.UNBOUNDED_LEASE)
        service.ensureLease(Duration.ofDays(365))
        service.stop()
    }

    @Test
    fun `locker exposes the lease of its contend service`() {
        val factory = CapturingFactory()
        val locker = SimbaLocker("m", factory)
        val service = factory.service!!
        service.start()
        assertThrows<LeaseExpiredException> { locker.ensureLease() }

        service.publishOwner(MutexOwner(locker.contenderId)).join()

        locker.leaseRemaining.assert().isEqualTo(MutexContendService.UNBOUNDED_LEASE)
        locker.ensureLease()
        locker.close()
    }

    @Test
    fun `locker implementations default to no lease`() {
        val locker = object : Locker {
            override fun acquire() = Unit
            override fun acquire(timeout: Duration) = Unit
            override fun close() = Unit
        }

        locker.leaseRemaining.assert().isEqualTo(Duration.ZERO)
        assertThrows<LeaseExpiredException> { locker.ensureLease() }
    }

    @Test
    fun `simba scheduler work sees the lease of its leadership term`() {
        val factory = CapturingFactory()
        val context = CompletableFuture<ScheduleContext>()
        val scheduler = SimbaScheduler("m", factory, ScheduleConfig.delay(Duration.ZERO, Duration.ofHours(1))) {
            context.complete(it)
        }
        val service = factory.service!!
        scheduler.start()

        service.publishOwner(MutexOwner(service.contenderId)).join()

        val leading = context.get(2, TimeUnit.SECONDS)
        leading.leaseRemaining.assert().isEqualTo(MutexContendService.UNBOUNDED_LEASE)
        leading.ensureLease()
        scheduler.stop()
        leading.leaseRemaining.assert().isEqualTo(Duration.ZERO)
        assertThrows<LeaseExpiredException> { leading.ensureLease() }
    }

    @Test
    fun `abstract scheduler work sees the lease of its leadership term`() {
        val factory = CapturingFactory()
        val scheduler = object : AbstractScheduler("m", factory) {
            override val config: ScheduleConfig = ScheduleConfig.delay(Duration.ofHours(1), Duration.ofHours(1))
            override val worker: String = "lease-worker"
            override fun work() = Unit
            fun remaining() = leaseRemaining
            fun ensure() = ensureLease()
        }
        val service = factory.service!!
        scheduler.start()
        assertThrows<LeaseExpiredException> { scheduler.ensure() }

        service.publishOwner(MutexOwner(service.contenderId)).join()

        scheduler.remaining().assert().isEqualTo(MutexContendService.UNBOUNDED_LEASE)
        scheduler.ensure()
        scheduler.stop()
    }

    @Test
    fun `schedule context implementations default to no lease`() {
        val context = object : ScheduleContext {
            override val mutex: String = "m"
            override val fencingToken: Long = MutexOwner.NO_FENCING_TOKEN
        }

        context.leaseRemaining.assert().isEqualTo(Duration.ZERO)
        assertThrows<LeaseExpiredException> { context.ensureLease() }.message!!.assert().contains("mutex:[m]")
    }

    private class CapturingFactory : MutexContendServiceFactory {
        var service: FakeMutexContendService? = null

        override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
            return FakeMutexContendService(mutexContender).also { service = it }
        }
    }
}
