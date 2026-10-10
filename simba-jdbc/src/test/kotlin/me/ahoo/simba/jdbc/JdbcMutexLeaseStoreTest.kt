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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.simba.core.LeaseConfig
import me.ahoo.simba.core.MutexOwner
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import java.time.Duration

class JdbcMutexLeaseStoreTest {
    private val config = LeaseConfig(Duration.ofSeconds(10), Duration.ofSeconds(6))

    @Test
    fun `contend delegates acquire and renew to acquireAndGetOwner with lease millis`() {
        val repository = mockk<MutexOwnerRepository>()
        val owner = MutexOwner("c1", 0, 10_000, 16_000)
        every { repository.acquireAndGetOwner("m", "c1", 10_000, 6_000) } returns owner
        val store = JdbcMutexLeaseStore(repository)

        store.contend("m", "c1", renew = false, config = config).assert().isSameAs(owner)
        store.contend("m", "c1", renew = true, config = config).assert().isSameAs(owner)
    }

    @Test
    fun `release delegates to repository`() {
        val repository = mockk<MutexOwnerRepository>()
        every { repository.release("m", "c1") } returns true

        JdbcMutexLeaseStore(repository).release("m", "c1").assert().isTrue()
    }
}
