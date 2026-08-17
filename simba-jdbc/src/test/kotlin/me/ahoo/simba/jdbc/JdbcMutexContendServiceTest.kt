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
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexState
import me.ahoo.simba.test.MutexContendServiceSpec
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author ahoo wang
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class JdbcMutexContendServiceTest : MutexContendServiceSpec() {

    private lateinit var jdbcMutexOwnerRepository: JdbcMutexOwnerRepository
    override lateinit var mutexContendServiceFactory: MutexContendServiceFactory

    @BeforeAll
    fun setup() {
        val hikariDataSource = HikariDataSource()
        hikariDataSource.jdbcUrl = "jdbc:mysql://localhost:3306/simba_db"
        hikariDataSource.username = "root"
        hikariDataSource.password = "root"
        jdbcMutexOwnerRepository = JdbcMutexOwnerRepository(hikariDataSource)
        mutexContendServiceFactory = JdbcMutexContendServiceFactory(
            mutexOwnerRepository = jdbcMutexOwnerRepository,
            initialDelay = Duration.ofSeconds(2),
            ttl = Duration.ofSeconds(2),
            transition = Duration.ofSeconds(5)
        )

        jdbcMutexOwnerRepository.tryInitMutex(START_MUTEX)
        jdbcMutexOwnerRepository.tryInitMutex(RESTART_MUTEX)
        jdbcMutexOwnerRepository.tryInitMutex(GUARD_MUTEX)
        jdbcMutexOwnerRepository.tryInitMutex(MULTI_CONTEND_MUTEX)
        jdbcMutexOwnerRepository.tryInitMutex(SCHEDULE_MUTEX)
    }

    @Test
    fun `old owner is revoked before another contender acquires after renewal failure`() {
        val mutex = "renewal-failure-${System.nanoTime()}"
        jdbcMutexOwnerRepository.ensureOwner(mutex)
        val ownerAAcquired = CountDownLatch(1)
        val ownerAReleased = CountDownLatch(1)
        val ownerBAcquired = CountDownLatch(1)
        val attempts = AtomicInteger()
        val eventSequence = AtomicInteger()
        val ownerAReleasedAt = AtomicInteger()
        val ownerBAcquiredAt = AtomicInteger()
        val partitionedRepository = object : MutexOwnerRepository by jdbcMutexOwnerRepository {
            override fun acquireAndGetOwner(
                mutex: String,
                contenderId: String,
                ttl: Long,
                transition: Long
            ): MutexOwnerEntity {
                if (attempts.getAndIncrement() == 0) {
                    return jdbcMutexOwnerRepository.acquireAndGetOwner(mutex, contenderId, ttl, transition)
                }
                throw IllegalStateException("owner A cannot renew")
            }
        }
        val ownerA = object : AbstractMutexContender(mutex, "owner-a") {
            override fun onAcquired(mutexState: MutexState) {
                ownerAAcquired.countDown()
            }

            override fun onReleased(mutexState: MutexState) {
                ownerAReleasedAt.set(eventSequence.incrementAndGet())
                ownerAReleased.countDown()
            }
        }
        val ownerB = object : AbstractMutexContender(mutex, "owner-b") {
            override fun onAcquired(mutexState: MutexState) {
                ownerBAcquiredAt.set(eventSequence.incrementAndGet())
                ownerBAcquired.countDown()
            }
        }
        val ownerAService = JdbcMutexContendService(
            ownerA,
            Runnable::run,
            partitionedRepository,
            Duration.ZERO,
            Duration.ofMillis(200),
            Duration.ofMillis(100)
        )
        val ownerBService = JdbcMutexContendService(
            ownerB,
            Runnable::run,
            jdbcMutexOwnerRepository,
            Duration.ZERO,
            Duration.ofMillis(200),
            Duration.ofMillis(100)
        )

        try {
            ownerAService.start()
            assertThat("owner A should acquire first", ownerAAcquired.await(2, TimeUnit.SECONDS))
            ownerBService.start()
            assertThat("owner A should revoke local ownership", ownerAReleased.await(2, TimeUnit.SECONDS))
            assertThat("owner B should acquire after A's lease expires", ownerBAcquired.await(5, TimeUnit.SECONDS))

            assertThat(ownerAReleasedAt.get() < ownerBAcquiredAt.get(), equalTo(true))
            assertThat(ownerAService.isOwner, equalTo(false))
            assertThat(ownerBService.isOwner, equalTo(true))
        } finally {
            if (ownerBService.running) {
                ownerBService.stop()
            }
            if (ownerAService.running) {
                ownerAService.stop()
            }
            jdbcMutexOwnerRepository.release(mutex, "owner-a")
            jdbcMutexOwnerRepository.release(mutex, "owner-b")
        }
    }
}
