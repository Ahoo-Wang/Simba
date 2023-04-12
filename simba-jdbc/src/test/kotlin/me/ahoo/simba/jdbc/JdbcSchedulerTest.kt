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
import me.ahoo.simba.schedule.AbstractScheduler
import me.ahoo.simba.schedule.ScheduleConfig
import me.ahoo.simba.test.MutexContendServiceSpec
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcSchedulerTest {
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

        jdbcMutexOwnerRepository.tryInitMutex(MutexContendServiceSpec.START_MUTEX)
        jdbcMutexOwnerRepository.tryInitMutex(MutexContendServiceSpec.RESTART_MUTEX)
        jdbcMutexOwnerRepository.tryInitMutex(MutexContendServiceSpec.GUARD_MUTEX)
        jdbcMutexOwnerRepository.tryInitMutex(MutexContendServiceSpec.MULTI_CONTEND_MUTEX)
    }

    @Test
    fun start() {
        val countDownLatch = CountDownLatch(0)
        val scheduler = object : AbstractScheduler(
            "start", contendServiceFactory
        ) {
            override val config: ScheduleConfig
                get() = ScheduleConfig.delay(
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1)
                )
            override val worker: String
                get() = "JdbcSchedulerTest"

            override fun work() {
                countDownLatch.countDown()
            }
        }
        scheduler.start()
        assertThat(scheduler.running, equalTo(true))
        assertThat(countDownLatch.await(3, TimeUnit.SECONDS), equalTo(true))
        scheduler.stop()
    }
}
