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

package me.ahoo.simba.spring.boot.starter.redis

import io.mockk.mockk
import me.ahoo.simba.redis.RedisLeaseBackend
import me.ahoo.simba.spi.LeaseBackend
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory

class SimbaRedisAutoConfigurationTest {
    private val withoutFactory = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SimbaRedisAutoConfiguration::class.java))
        .withPropertyValues("simba.backend=redis")
    private val contextRunner = withoutFactory
        .withBean(RedisConnectionFactory::class.java, { mockk<LettuceConnectionFactory>(relaxed = true) })

    @Test
    fun `a connection factory gets a redis lease backend`() {
        contextRunner.run { assertThat(it).hasSingleBean(RedisLeaseBackend::class.java) }
    }

    @Test
    fun `replica wait and slot routing come from the properties`() {
        contextRunner
            .withPropertyValues(
                "simba.redis.wait-replicas=1",
                "simba.redis.wait-timeout=200ms",
                "simba.redis.route-by-slot=true"
            )
            .run { assertThat(it).hasNotFailed().hasSingleBean(RedisLeaseBackend::class.java) }
    }

    @Test
    fun `a connection factory other than lettuce fails startup`() {
        withoutFactory
            .withBean(RedisConnectionFactory::class.java, { mockk<RedisConnectionFactory>(relaxed = true) })
            .run {
                assertThat(it).hasFailed()
                assertThat(it.startupFailure).rootCause().hasMessageContaining("needs a Lettuce RedisConnectionFactory")
            }
    }

    @Test
    fun `disabled redis backend stays off`() {
        contextRunner.withPropertyValues("simba.redis.enabled=false").run {
            assertThat(it).doesNotHaveBean(LeaseBackend::class.java)
        }
    }
}
