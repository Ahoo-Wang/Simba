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
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

class FencingTokenTest {
    @Test
    fun `owners default to no fencing token`() {
        MutexOwner("c1").fencingToken.assert().isEqualTo(MutexOwner.NO_FENCING_TOKEN)
        MutexOwner.NONE.fencingToken.assert().isEqualTo(MutexOwner.NO_FENCING_TOKEN)
    }

    @Test
    fun `contend service exposes the token only while owner`() {
        val contender = FakeMutexContender("m", "c1")
        val service = FakeMutexContendService(contender)
        service.start()

        service.publishOwner(MutexOwner("c1", fencingToken = 42)).join()
        service.fencingToken.assert().isEqualTo(42)

        service.publishOwner(MutexOwner("c2", fencingToken = 43)).join()
        service.fencingToken.assert().isEqualTo(MutexOwner.NO_FENCING_TOKEN)
        service.stop()
    }

    @Test
    fun `locker exposes the token of its contend service`() {
        val factory = CapturingFactory()
        val locker = SimbaLocker("m", factory)
        val service = factory.service!!
        service.start()

        service.publishOwner(MutexOwner(locker.contenderId, fencingToken = 7)).join()

        locker.fencingToken.assert().isEqualTo(7)
        locker.close()
    }

    @Test
    fun `locker implementations default to no fencing token`() {
        val locker = object : Locker {
            override fun acquire() = Unit
            override fun acquire(timeout: Duration) = Unit
            override fun close() = Unit
        }

        locker.fencingToken.assert().isEqualTo(MutexOwner.NO_FENCING_TOKEN)
    }

    @Test
    fun `scheduler work sees the token of its leadership term`() {
        val factory = CapturingFactory()
        val scheduler = object : AbstractScheduler("m", factory) {
            override val config: ScheduleConfig = ScheduleConfig.delay(Duration.ofHours(1), Duration.ofHours(1))
            override val worker: String = "fencing-worker"
            override fun work() = Unit
            fun token() = fencingToken
        }
        val service = factory.service!!
        scheduler.start()

        service.publishOwner(MutexOwner(service.contenderId, fencingToken = 9)).join()

        scheduler.token().assert().isEqualTo(9)
        scheduler.stop()
    }

    private class CapturingFactory : MutexContendServiceFactory {
        var service: FakeMutexContendService? = null

        override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
            return FakeMutexContendService(mutexContender).also { service = it }
        }
    }
}
