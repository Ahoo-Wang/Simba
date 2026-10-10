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
import me.ahoo.simba.core.MutexContendService
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZookeeperFencingTokenTest {
    private lateinit var testingServer: TestingServer
    private lateinit var curatorFramework: CuratorFramework

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

    @Test
    fun `every leadership term gets a strictly larger token`() {
        val mutex = "fencing-${System.nanoTime()}"
        val first = leadOnce(mutex, "a")
        val second = leadOnce(mutex, "b")
        val third = leadOnce(mutex, "a")

        first.assert().isGreaterThan(0)
        second.assert().isGreaterThan(first)
        third.assert().isGreaterThan(second)
    }

    @Test
    fun `token stays monotonic after the latch parent container is recreated`() {
        val mutex = "fencing-container-${System.nanoTime()}"
        val before = leadOnce(mutex, "a")
        // Simulate ZooKeeper reaping the empty container: its children's sequence would restart from zero.
        curatorFramework.delete().forPath(ZookeeperMutexContendService.RESOURCE_PREFIX + mutex)

        val after = leadOnce(mutex, "b")

        after.assert().isGreaterThan(before)
    }

    private fun leadOnce(mutex: String, contenderId: String): Long {
        val acquired = CountDownLatch(1)
        val contender = object : AbstractMutexContender(mutex, contenderId) {
            override fun onAcquired(mutexState: MutexState) {
                acquired.countDown()
            }
        }
        val service: MutexContendService =
            ZookeeperMutexContendService(contender, Executor { it.run() }, curatorFramework)
        service.start()
        try {
            acquired.await(10, TimeUnit.SECONDS).assert().isTrue()
            return service.fencingToken
        } finally {
            service.stop()
        }
    }
}
