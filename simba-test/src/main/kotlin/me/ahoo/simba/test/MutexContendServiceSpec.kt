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

package me.ahoo.simba.test

import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexState
import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

abstract class MutexContendServiceSpec {
    companion object {
        private val log = LoggerFactory.getLogger(MutexContendServiceSpec::class.java)
        const val START_MUTEX = "start"
        const val RESTART_MUTEX = "restart"
        const val GUARD_MUTEX = "guard"
        const val MULTI_CONTEND_MUTEX = "multiContend"
        const val SCHEDULE_MUTEX = "schedule"
    }

    abstract val mutexContendServiceFactory: MutexContendServiceFactory

    @Test
    open fun start() {
        val acquiredFuture = CompletableFuture<MutexOwner>()
        val releasedFuture = CompletableFuture<MutexOwner>()
        val contendService = mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
            START_MUTEX
        ) {
            override fun onAcquired(mutexState: MutexState) {
                log.info("onAcquired")
                acquiredFuture.complete(mutexState.after)
            }

            override fun onReleased(mutexState: MutexState) {
                log.info("onReleased")
                releasedFuture.complete(mutexState.after)
            }
        })
        contendService.start()
        acquiredFuture.join()
        assertThat(contendService.isOwner, equalTo(true))
        contendService.stop()
        releasedFuture.join()
        assertThat(contendService.isOwner, equalTo(false))
    }

    @Test
    open fun restart() {
        val acquiredFuture = CompletableFuture<MutexOwner>()
        val releasedFuture = CompletableFuture<MutexOwner>()
        val acquiredFuture2 = CompletableFuture<MutexOwner>()
        val releasedFuture2 = CompletableFuture<MutexOwner>()
        val contendService = mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
            RESTART_MUTEX
        ) {
            override fun onAcquired(mutexState: MutexState) {
                log.info("onAcquired")
                if (!acquiredFuture.isDone) {
                    acquiredFuture.complete(mutexState.after)
                } else {
                    acquiredFuture2.complete(mutexState.after)
                }
            }

            override fun onReleased(mutexState: MutexState) {
                log.info("onReleased")
                if (!releasedFuture.isDone) {
                    releasedFuture.complete(mutexState.after)
                } else {
                    releasedFuture2.complete(mutexState.after)
                }
            }
        })
        contendService.start()
        acquiredFuture.join()
        assertThat(contendService.isOwner, equalTo(true))
        contendService.stop()
        releasedFuture.join()
        assertThat(contendService.isOwner, equalTo(false))
        contendService.start()
        acquiredFuture2.join()
        assertThat(contendService.isOwner, equalTo(true))
        contendService.stop()
        releasedFuture2.join()
        assertThat(contendService.isOwner, equalTo(false))
    }

    @Test
    open fun guard() {
        val acquiredFuture = CompletableFuture<MutexOwner>()
        val releasedFuture = CompletableFuture<MutexOwner>()
        val contendService = mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
            GUARD_MUTEX
        ) {
            override fun onAcquired(mutexState: MutexState) {
                log.info("onAcquired")
                acquiredFuture.complete(mutexState.after)
            }

            override fun onReleased(mutexState: MutexState) {
                log.info("onReleased")
                releasedFuture.complete(mutexState.after)
            }
        })
        contendService.start()
        acquiredFuture.join()
        assertThat(contendService.isOwner, equalTo(true))
        TimeUnit.SECONDS.sleep(3)
        assertThat(contendService.afterOwner.ownerId, equalTo(contendService.contender.contenderId))
        assertThat(contendService.isOwner, equalTo(true))
        contendService.stop()
        releasedFuture.join()
        assertThat(contendService.isOwner, equalTo(false))
    }

    @Test
    open fun multiContend() {
        val count = AtomicInteger(0)
        val currentOwnerIdRef = AtomicReference<String>()
        val contendServiceList: MutableList<MutexContendService> = ArrayList(10)
        for (i in 0..9) {
            val contendService =
                mutexContendServiceFactory.createMutexContendService(object : AbstractMutexContender(
                    MULTI_CONTEND_MUTEX
                ) {
                    override fun onAcquired(mutexState: MutexState) {
                        currentOwnerIdRef.set(mutexState.after.ownerId)
                        super.onAcquired(mutexState)
                        assertThat(count.incrementAndGet(), equalTo(1))
                    }

                    override fun onReleased(mutexState: MutexState) {
                        super.onReleased(mutexState)
                        assertThat(count.decrementAndGet(), equalTo(0))
                    }
                })
            contendService.start()
            contendServiceList.add(contendService)
        }
        TimeUnit.SECONDS.sleep(30)
        assertThat(count.get(), equalTo(1))
        val currentOwnerId = currentOwnerIdRef.get()
        for (contendService in contendServiceList) {
            if (contendService.afterOwner.ownerId.isNotBlank()) {
                assertThat(contendService.afterOwner.ownerId, equalTo(currentOwnerId))
            }
        }
        val ownerCount = contendServiceList.count { it.contenderId == currentOwnerId }
        assertThat(ownerCount, equalTo(1))
    }

    @Test
    fun schedule() {
        val countDownLatch = CountDownLatch(1)
        val config = ScheduleConfig.delay(Duration.ZERO, Duration.ofSeconds(1))
        val worker = "Test Worker"
        val testScheduler = object : AbstractScheduler(SCHEDULE_MUTEX, mutexContendServiceFactory) {
            override val config: ScheduleConfig
                get() = config
            override val worker: String
                get() = worker

            override fun work() {
                countDownLatch.countDown()
            }
        }
        assertThat(testScheduler.running, equalTo(false))
        testScheduler.start()
        assertThat(testScheduler.running, equalTo(true))
        assertThat(countDownLatch.await(5, TimeUnit.SECONDS), equalTo(true))
        testScheduler.stop()
        assertThat(testScheduler.running, equalTo(false))
    }
}
