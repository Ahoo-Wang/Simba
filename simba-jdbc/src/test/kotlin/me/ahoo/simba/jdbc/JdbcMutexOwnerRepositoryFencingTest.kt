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
import me.ahoo.simba.core.MutexOwner
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Fencing tokens against MySQL loaded with `init-simba-mysql.sql`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JdbcMutexOwnerRepositoryFencingTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: JdbcMutexOwnerRepository

    @BeforeAll
    fun setup() {
        dataSource = HikariDataSource()
        dataSource.jdbcUrl = "jdbc:mysql://localhost:3306/simba_db"
        dataSource.username = "root"
        dataSource.password = "root"
        repository = JdbcMutexOwnerRepository(dataSource, fencing = true)
    }

    @AfterAll
    fun destroy() {
        dataSource.close()
    }

    @Test
    fun `token advances per term and stays stable across renewals`() {
        val mutex = newMutex()

        val first = repository.acquireAndGetOwner(mutex, "a", LONG_TTL, LONG_TRANSITION)
        first.ownerId.assert().isEqualTo("a")
        val renewed = repository.acquireAndGetOwner(mutex, "a", LONG_TTL, LONG_TRANSITION)
        val observed = repository.acquireAndGetOwner(mutex, "b", LONG_TTL, LONG_TRANSITION)
        repository.release(mutex, "a").assert().isTrue()
        val second = repository.acquireAndGetOwner(mutex, "b", LONG_TTL, LONG_TRANSITION)

        first.fencingToken.assert().isEqualTo(1)
        renewed.fencingToken.assert().isEqualTo(first.fencingToken)
        observed.ownerId.assert().isEqualTo("a")
        observed.fencingToken.assert().isEqualTo(first.fencingToken)
        second.ownerId.assert().isEqualTo("b")
        second.fencingToken.assert().isEqualTo(first.fencingToken + 1)
    }

    @Test
    fun `re-acquiring after the own lease ended starts a new term`() {
        val mutex = newMutex()
        val first = repository.acquireAndGetOwner(mutex, "a", 1, 0)
        awaitLeaseEnd(mutex)

        val second = repository.acquireAndGetOwner(mutex, "a", LONG_TTL, LONG_TRANSITION)

        second.ownerId.assert().isEqualTo("a")
        second.fencingToken.assert().isEqualTo(first.fencingToken + 1)
    }

    @Test
    fun `fencing disabled reports no token`() {
        val mutex = newMutex()
        val plain = JdbcMutexOwnerRepository(dataSource)

        plain.acquireAndGetOwner(mutex, "a", LONG_TTL, LONG_TRANSITION).fencingToken
            .assert().isEqualTo(MutexOwner.NO_FENCING_TOKEN)
    }

    private fun awaitLeaseEnd(mutex: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            val owner = repository.getOwner(mutex)
            if (owner.transitionAt < owner.currentAt) {
                return
            }
            Thread.onSpinWait()
        }
        error("lease of [$mutex] did not end")
    }

    private fun newMutex() = "fencing-${UUID.randomUUID().toString().take(8)}"

    companion object {
        private const val LONG_TTL = 60_000L
        private const val LONG_TRANSITION = 10_000L
    }
}
