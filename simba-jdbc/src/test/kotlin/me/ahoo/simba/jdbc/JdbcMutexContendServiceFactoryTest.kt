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

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Proxy
import java.time.Duration

class JdbcMutexContendServiceFactoryTest {
    private val repository = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(MutexOwnerRepository::class.java)
    ) { _, _, _ -> throw UnsupportedOperationException() } as MutexOwnerRepository

    @Test
    fun `factory rejects invalid scheduling and lease durations`() {
        assertThrows<IllegalArgumentException> {
            newFactory(Duration.ofMillis(-1), Duration.ofMillis(1), Duration.ZERO)
        }
        assertThrows<IllegalArgumentException> { newFactory(Duration.ZERO, Duration.ZERO, Duration.ZERO) }
        assertThrows<IllegalArgumentException> { newFactory(Duration.ZERO, Duration.ofNanos(1), Duration.ZERO) }
        assertThrows<IllegalArgumentException> {
            newFactory(Duration.ZERO, Duration.ofMillis(1), Duration.ofMillis(-1))
        }
        assertThrows<IllegalArgumentException> {
            newFactory(Duration.ZERO, Duration.ofMillis(Long.MAX_VALUE), Duration.ofMillis(1))
        }
        assertThrows<IllegalArgumentException> {
            newFactory(Duration.ofSeconds(Long.MAX_VALUE), Duration.ofMillis(1), Duration.ZERO)
        }
    }

    private fun newFactory(
        initialDelay: Duration,
        ttl: Duration,
        transition: Duration
    ): JdbcMutexContendServiceFactory {
        return JdbcMutexContendServiceFactory(
            mutexOwnerRepository = repository,
            initialDelay = initialDelay,
            ttl = ttl,
            transition = transition
        )
    }
}
