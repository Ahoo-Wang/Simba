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
package me.ahoo.simba.zookeeper

import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexState
import me.ahoo.test.asserts.assert
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZookeeperRelinquishTest {
    private lateinit var testingServer: TestingServer
    private lateinit var curatorFramework: CuratorFramework
    private val acquired = LinkedBlockingQueue<MutexState>()

    @BeforeAll
    fun setup() {
        testingServer = TestingServer()
        testingServer.start()
        curatorFramework = CuratorFrameworkFactory.newClient(testingServer.connectString, RetryNTimes(1, 10))
        curatorFramework.start()
    }

    @AfterAll
    fun destroy() {
        curatorFramework.close()
        testingServer.stop()
    }

    private fun newService(): ZookeeperMutexContendService {
        val contender = object : AbstractMutexContender("relinquish-${System.nanoTime()}") {
            override fun onAcquired(mutexState: MutexState) {
                acquired.add(mutexState)
            }
        }
        return ZookeeperMutexContendService(contender, Executor { it.run() }, curatorFramework)
    }

    @Test
    fun `relinquish requires a running service and a non-negative cooldown`() {
        val service = newService()
        assertThrows<IllegalStateException> { service.relinquish(Duration.ZERO) }
        service.start()
        assertThrows<IllegalArgumentException> { service.relinquish(Duration.ofMillis(-1)) }
        service.stop()
    }

    @Test
    fun `stop during the cooldown releases without a latch and never rejoins`() {
        val service = newService()
        service.start()
        acquired.poll(30, TimeUnit.SECONDS).assert().isNotNull()

        service.relinquish(Duration.ofHours(1))
        service.isOwner.assert().isFalse()
        service.stop()

        service.running.assert().isFalse()
    }

    @Test
    fun `a later relinquish replaces the earlier cooldown`() {
        val service = newService()
        service.start()
        acquired.poll(30, TimeUnit.SECONDS).assert().isNotNull()

        service.relinquish(Duration.ofHours(1))
        // A non-owner relinquishing again only replaces the cooldown; the first rejoin is dropped.
        service.relinquish(Duration.ofMillis(50))

        acquired.poll(30, TimeUnit.SECONDS).assert().isNotNull()
        service.isOwner.assert().isTrue()
        service.stop()
    }
}
