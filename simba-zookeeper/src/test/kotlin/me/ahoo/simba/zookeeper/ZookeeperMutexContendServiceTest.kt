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

import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexRetrievalService.Status
import me.ahoo.simba.core.MutexState
import me.ahoo.simba.test.MutexContendServiceSpec
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryNTimes
import org.apache.curator.test.TestingServer
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.concurrent.Executor
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe counting contender: LeaderLatch callbacks arrive on the Curator
 * EventThread while assertions poll from the test thread.
 */
private class CountingContender(
    override val mutex: String,
    override val contenderId: String
) : MutexContender {
    val acquiredCount = AtomicInteger()

    override fun onAcquired(mutexState: MutexState) {
        acquiredCount.incrementAndGet()
    }

    override fun onReleased(mutexState: MutexState) = Unit
}

/**
 * @author ahoo wang
 */

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ZookeeperMutexContendServiceTest : MutexContendServiceSpec() {
    lateinit var curatorFramework: CuratorFramework
    override lateinit var mutexContendServiceFactory: MutexContendServiceFactory
    lateinit var testingServer: TestingServer

    @BeforeAll
    fun setup() {
        testingServer = TestingServer()
        testingServer.start()
        curatorFramework = CuratorFrameworkFactory.newClient(testingServer.connectString, RetryNTimes(1, 10))
        curatorFramework.start()
        mutexContendServiceFactory = ZookeeperMutexContendServiceFactory(ForkJoinPool.commonPool(), curatorFramework)
    }

    @AfterAll
    fun destroy() {
        if (this::curatorFramework.isInitialized) {
            curatorFramework.close()
        }
        if (this::testingServer.isInitialized) {
            testingServer.stop()
        }
    }

    @Test
    fun `late isLeader callback after stop must not revive ownership`() {
        val mutex = "late-is-leader-${System.currentTimeMillis()}"
        val contender = CountingContender(mutex, "late-callback-contender")
        // synchronous handleExecutor: notifyOwner applies inline on the calling thread
        val directExecutor = Executor { it.run() }
        val contendService = ZookeeperMutexContendService(contender, directExecutor, curatorFramework)

        contendService.start()

        // the sole contender genuinely becomes leader shortly after start
        val deadline = System.currentTimeMillis() + 10_000
        while (contender.acquiredCount.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertThat("sole contender should become leader", contender.acquiredCount.get(), equalTo(1))

        contendService.stop()
        assertThat(contendService.status, equalTo(Status.INITIAL))
        assertThat("state after stop should be owner-less", contendService.afterOwner, equalTo(MutexOwner.NONE))

        // simulate a LeaderLatch callback dispatched by the Curator EventThread
        // racing with close() and arriving after stop completed
        contendService.isLeader()

        assertThat(
            "late isLeader must be ignored after stop",
            contender.acquiredCount.get(),
            equalTo(1)
        )
        assertThat("mutexState must stay owner-less after stop", contendService.afterOwner, equalTo(MutexOwner.NONE))
    }
}
