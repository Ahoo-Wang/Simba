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
import me.ahoo.simba.core.ContenderIdGenerator
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * @author ahoo wang
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class JdbcMutexOwnerRepositoryTest {
    private lateinit var jdbcMutexOwnerRepository: JdbcMutexOwnerRepository

    @BeforeAll
    fun setup() {
        val hikariDataSource = HikariDataSource()
        hikariDataSource.jdbcUrl = "jdbc:mysql://localhost:3306/simba_db"
        hikariDataSource.username = "root"
        hikariDataSource.password = "root"
        jdbcMutexOwnerRepository = JdbcMutexOwnerRepository(hikariDataSource)
    }

    @Test
    fun getOwner() {
        jdbcMutexOwnerRepository.tryInitMutex("getOwner")
        val entity = jdbcMutexOwnerRepository.getOwner("getOwner")
        Assertions.assertNotNull(entity)
    }

    @Test
    fun acquire() {
        val ttl: Long = 100
        val transition: Long = 100
        val ownerId: String = ContenderIdGenerator.HOST.generate()
        val mutex = "acquire_$ownerId"
        jdbcMutexOwnerRepository.tryInitMutex(mutex)
        var succeeded = jdbcMutexOwnerRepository.acquire(mutex, ownerId, ttl, transition)
        Assertions.assertTrue(succeeded)
        val entity = jdbcMutexOwnerRepository.getOwner(mutex)
        Assertions.assertNotNull(entity)
        Assertions.assertEquals(ownerId, entity.ownerId)
        succeeded = jdbcMutexOwnerRepository.acquire(mutex, ownerId, ttl, transition)
        Assertions.assertTrue(succeeded)
    }
}
