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

import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexState
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JdbcMutexContendServiceRenewalFailureTest {
    @Test
    fun `renewal failure revokes local ownership`() {
        val acquired = CountDownLatch(1)
        val renewalAttempted = CountDownLatch(1)
        val released = CountDownLatch(1)
        val attempts = AtomicInteger()
        val repository = object : MutexOwnerRepository {
            override fun initMutex(mutex: String) = true
            override fun tryInitMutex(mutex: String) = true

            override fun getOwner(mutex: String): MutexOwnerEntity {
                throw NotFoundMutexOwnerException("not expected in this test")
            }

            override fun acquire(mutex: String, contenderId: String, ttl: Long, transition: Long) = false

            override fun acquireAndGetOwner(
                mutex: String,
                contenderId: String,
                ttl: Long,
                transition: Long
            ): MutexOwnerEntity {
                if (attempts.getAndIncrement() == 0) {
                    val now = System.currentTimeMillis()
                    return MutexOwnerEntity(mutex, contenderId, now, now + 50, now + 100).also {
                        it.currentDbAt = now
                    }
                }
                renewalAttempted.countDown()
                throw IllegalStateException("database unavailable")
            }

            override fun release(mutex: String, contenderId: String) = true

            override fun ensureOwner(mutex: String): MutexOwnerEntity {
                throw NotFoundMutexOwnerException("not expected in this test")
            }
        }
        val contender = object : AbstractMutexContender("lease-expiry") {
            override fun onAcquired(mutexState: MutexState) {
                acquired.countDown()
            }

            override fun onReleased(mutexState: MutexState) {
                released.countDown()
            }
        }
        val contendService = JdbcMutexContendService(
            contender,
            Executor { it.run() },
            repository,
            Duration.ZERO,
            Duration.ofMillis(100),
            Duration.ofMillis(50)
        )

        try {
            contendService.start()
            assertThat("initial acquisition should succeed", acquired.await(1, TimeUnit.SECONDS))
            assertThat("renewal should be attempted", renewalAttempted.await(1, TimeUnit.SECONDS))
            assertThat("renewal failure should revoke local ownership", released.await(300, TimeUnit.MILLISECONDS))
            assertThat(contendService.isOwner, equalTo(false))
        } finally {
            if (contendService.running) {
                contendService.stop()
            }
        }
    }
}
