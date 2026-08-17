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
package me.ahoo.simba.spring.boot.starter

import io.mockk.mockk
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.spring.boot.starter.jdbc.SimbaJdbcAutoConfiguration
import me.ahoo.simba.spring.boot.starter.redis.SimbaSpringRedisAutoConfiguration
import me.ahoo.simba.spring.boot.starter.zookeeper.SimbaZookeeperAutoConfiguration
import me.ahoo.simba.spring.redis.SpringRedisMutexContendServiceFactory
import org.apache.curator.framework.CuratorFramework
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import javax.sql.DataSource

/**
 * When multiple backends are on the classpath, exactly one contend service factory
 * must be registered, following the documented selection order
 * Redis -> JDBC -> Zookeeper.
 */
internal class MultipleBackendsAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withBean(DataSource::class.java, { mockk() })
        .withBean(StringRedisTemplate::class.java, { mockk(relaxed = true) })
        .withBean(RedisMessageListenerContainer::class.java, { mockk(relaxed = true) })
        .withBean(CuratorFramework::class.java, { mockk(relaxed = true) })
        .withConfiguration(
            AutoConfigurations.of(
                SimbaJdbcAutoConfiguration::class.java,
                SimbaSpringRedisAutoConfiguration::class.java,
                SimbaZookeeperAutoConfiguration::class.java
            )
        )

    @Test
    fun redisTakesPrecedenceOverJdbcAndZookeeper() {
        contextRunner.run {
            assertThat(it).hasSingleBean(MutexContendServiceFactory::class.java)
            assertThat(it).getBean(MutexContendServiceFactory::class.java)
                .isInstanceOf(SpringRedisMutexContendServiceFactory::class.java)
        }
    }

    @Test
    fun jdbcTakesPrecedenceOverZookeeperWhenRedisAbsent() {
        contextRunner
            .withPropertyValues("simba.redis.enabled=false")
            .run {
                assertThat(it).hasSingleBean(MutexContendServiceFactory::class.java)
                assertThat(it).getBean(MutexContendServiceFactory::class.java)
                    .isInstanceOf(me.ahoo.simba.jdbc.JdbcMutexContendServiceFactory::class.java)
            }
    }
}
