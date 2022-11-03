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
import me.ahoo.simba.locker.SimbaLocker
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.TimeoutException

/**
 * @author ahoo wang
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcLockerTest {
    companion object {
        private val log = LoggerFactory.getLogger(JdbcLockerTest::class.java)
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
    fun test() {
        val mutex = "locker-test"
        jdbcMutexOwnerRepository.tryInitMutex(mutex)
        SimbaLocker(mutex, contendServiceFactory).use { locker ->
            locker.acquire()
            log.info("acquired")
        }
    }

    @Test
    fun testTimeout() {
        val mutex = "locker-test"
        jdbcMutexOwnerRepository.tryInitMutex(mutex)
        SimbaLocker(mutex, contendServiceFactory).use { locker ->
            Assertions.assertThrows(TimeoutException::class.java) { locker.acquire(Duration.ofSeconds(0)) }
            log.info("acquired")
        }
    }
}
