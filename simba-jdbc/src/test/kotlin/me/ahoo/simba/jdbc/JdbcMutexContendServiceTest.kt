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
package me.ahoo.simba.jdbc

import com.zaxxer.hikari.HikariDataSource
import lombok.SneakyThrows
import lombok.extern.slf4j.Slf4j
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.core.MutexState
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * @author ahoo wang
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class JdbcMutexContendServiceTest {
    companion object {
        private val log = LoggerFactory.getLogger(JdbcMutexContendServiceTest::class.java)
    }

    private lateinit var jdbcMutexOwnerRepository: JdbcMutexOwnerRepository
    private lateinit var contendServiceFactory: JdbcMutexContendServiceFactory

    @BeforeAll
    fun setup() {
        val hikariDataSource = HikariDataSource()
        hikariDataSource.jdbcUrl = "jdbc:mysql://localhost:3306/simba_db"
        hikariDataSource.username = "root"
        hikariDataSource.password = "root"
        jdbcMutexOwnerRepository = JdbcMutexOwnerRepository(hikariDataSource)
        contendServiceFactory = JdbcMutexContendServiceFactory(
            mutexOwnerRepository = jdbcMutexOwnerRepository,
            initialDelay = Duration.ofSeconds(2),
            ttl = Duration.ofSeconds(2),
            transition = Duration.ofSeconds(5)
        )
    }

    @Test
    fun start() {
        val mutex = "start"
        val acquiredFuture = CompletableFuture<MutexOwner>()
        val releasedFuture = CompletableFuture<MutexOwner>()
        val contendService = contendServiceFactory.createMutexContendService(object : AbstractMutexContender(mutex) {
            override fun onAcquired(mutexState: MutexState) {
                log.info("onAcquired")
                acquiredFuture.complete(mutexState.after)
            }

            override fun onReleased(mutexState: MutexState) {
                log.info("onReleased")
                releasedFuture.complete(mutexState.after)
            }
        }) as JdbcMutexContendService
        contendService.start()
        acquiredFuture.join()
        Assertions.assertTrue(contendService.isOwner)
        contendService.stop()
        releasedFuture.join()
        Assertions.assertFalse(contendService.isOwner)
    }

    @Test
    fun restart() {
        val mutex = "restart"
        jdbcMutexOwnerRepository.tryInitMutex(mutex)
        val acquiredFuture = CompletableFuture<MutexOwner>()
        val releasedFuture = CompletableFuture<MutexOwner>()
        val acquiredFuture2 = CompletableFuture<MutexOwner>()
        val releasedFuture2 = CompletableFuture<MutexOwner>()
        val contendService = contendServiceFactory.createMutexContendService(object : AbstractMutexContender(mutex) {
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
        }) as JdbcMutexContendService
        contendService.start()
        acquiredFuture.join()
        Assertions.assertTrue(contendService.isOwner)
        contendService.stop()
        releasedFuture.join()
        Assertions.assertFalse(contendService.isOwner)
        contendService.start()
        acquiredFuture2.join()
        Assertions.assertTrue(contendService.isOwner)
        contendService.stop()
        releasedFuture2.join()
        Assertions.assertFalse(contendService.isOwner)
    }

    @SneakyThrows
    @Test
    fun guard() {
        val mutex = "guard"
        jdbcMutexOwnerRepository.tryInitMutex(mutex)
        val acquiredFuture = CompletableFuture<MutexOwner>()
        val releasedFuture = CompletableFuture<MutexOwner>()
        val contendService = contendServiceFactory.createMutexContendService(object : AbstractMutexContender(mutex) {
            override fun onAcquired(mutexState: MutexState) {
                log.info("onAcquired")
                acquiredFuture.complete(mutexState.after)
            }

            override fun onReleased(mutexState: MutexState) {
                log.info("onReleased")
                releasedFuture.complete(mutexState.after)
            }
        }) as JdbcMutexContendService
        contendService.start()
        acquiredFuture.join()
        Assertions.assertTrue(contendService.isOwner)
        TimeUnit.SECONDS.sleep(3)
        Assertions.assertEquals(contendService.afterOwner.ownerId, contendService.contender.contenderId)
        Assertions.assertTrue(contendService.isOwner)
        contendService.stop()
        releasedFuture.join()
        Assertions.assertFalse(contendService.isOwner)
    }

    @Test
    @Throws(InterruptedException::class)
    fun multiContend() {
        val mutex = "multiContend"
        jdbcMutexOwnerRepository.tryInitMutex(mutex)
        val count = AtomicInteger(0)
        val currentOwnerIdRef = AtomicReference<String>()
        val contendServiceList: MutableList<JdbcMutexContendService> = ArrayList(10)
        for (i in 0..9) {
            val contendService =
                contendServiceFactory.createMutexContendService(object : AbstractMutexContender(mutex) {
                    override fun onAcquired(mutexState: MutexState) {
                        currentOwnerIdRef.set(mutexState.after.ownerId)
                        super.onAcquired(mutexState)
                        Assertions.assertEquals(1, count.incrementAndGet())
                    }

                    override fun onReleased(mutexState: MutexState) {
                        super.onReleased(mutexState)
                        Assertions.assertEquals(0, count.decrementAndGet())
                    }
                }) as JdbcMutexContendService
            contendService.start()
            contendServiceList.add(contendService)
        }
        TimeUnit.SECONDS.sleep(30)
        Assertions.assertEquals(1, count.get())
        val currentOwnerId = currentOwnerIdRef.get()
        for (contendService in contendServiceList) {
            if (contendService.afterOwner.ownerId.isNotBlank()) {
                Assertions.assertEquals(contendService.afterOwner.ownerId, currentOwnerId)
            }
        }
        Assertions.assertEquals(
            1,
            contendServiceList.stream()
                .filter { contendService: JdbcMutexContendService -> contendService.contenderId == currentOwnerId }
                .count())
    }
}
