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
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexRetrievalService.Status
import me.ahoo.simba.core.MutexState
import me.ahoo.test.asserts.assert
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.framework.api.ExistsBuilder
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.KillSession
import org.apache.curator.test.TestingServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Zookeeper failure modes: session expiry, unusable clients and fencing token lookup failures.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ZookeeperFailureTest {
    private lateinit var testingServer: TestingServer

    @BeforeAll
    fun setup() {
        testingServer = TestingServer()
    }

    @AfterAll
    fun destroy() {
        testingServer.close()
    }

    private fun newClient(): CuratorFramework {
        return CuratorFrameworkFactory.newClient(testingServer.connectString, RetryNTimes(20, 100))
            .also { it.start() }
    }

    private class RecordingContender(mutex: String) : AbstractMutexContender(mutex) {
        val events = LinkedBlockingQueue<String>()

        override fun onAcquired(mutexState: MutexState) {
            events.add("acquired:${mutexState.after.fencingToken}")
        }

        override fun onReleased(mutexState: MutexState) {
            events.add("released")
        }
    }

    private fun LinkedBlockingQueue<String>.next(): String = checkNotNull(poll(30, TimeUnit.SECONDS)) {
        "no event within 30s"
    }

    @Test
    fun `session expiry revokes leadership and the next term has a higher fencing token`() {
        newClient().use { client ->
            val contender = RecordingContender("session-expiry")
            val contendService = ZookeeperMutexContendService(contender, ForkJoinPool.commonPool(), client)
            contendService.start()
            val firstToken = contender.events.next().removePrefix("acquired:").toLong()

            KillSession.kill(client.zookeeperClient.zooKeeper)

            contender.events.next().assert().isEqualTo("released")
            val secondToken = contender.events.next().removePrefix("acquired:").toLong()
            secondToken.assert().isGreaterThan(firstToken)
            contendService.stop()
            contender.events.next().assert().isEqualTo("released")
        }
    }

    @Test
    fun `a closed client never acquires and stop still completes`() {
        val client = newClient()
        client.close()
        val contender = RecordingContender("closed-client")
        val contendService = ZookeeperMutexContendService(contender, ForkJoinPool.commonPool(), client)

        contendService.start()

        contender.events.poll(1, TimeUnit.SECONDS).assert().isNull()
        contendService.stop()
        contendService.status.assert().isEqualTo(Status.INITIAL)
        contender.events.assert().isEmpty()
    }

    @Test
    fun `leadership without a readable latch node has no fencing token`() {
        newClient().use { client ->
            val unreadable = object : CuratorFramework by client {
                override fun checkExists(): ExistsBuilder = throw IllegalStateException("exists unavailable")
            }
            val contender = RecordingContender("unreadable-node")
            val contendService = ZookeeperMutexContendService(contender, ForkJoinPool.commonPool(), unreadable)
            contendService.start()

            contender.events.next().assert().isEqualTo("acquired:${MutexOwner.NO_FENCING_TOKEN}")
            contendService.stop()
        }
    }
}
