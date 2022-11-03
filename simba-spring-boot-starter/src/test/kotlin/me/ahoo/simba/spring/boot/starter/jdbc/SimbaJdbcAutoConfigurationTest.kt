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
package me.ahoo.simba.spring.boot.starter.jdbc

import io.mockk.mockk
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.jdbc.MutexOwnerRepository
import org.assertj.core.api.AssertionsForInterfaceTypes.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Duration
import javax.sql.DataSource

/**
 * SimbaJdbcAutoConfigurationTest .
 *
 * @author ahoo wang
 */
internal class SimbaJdbcAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()

    @Test
    fun contextLoads() {
        contextRunner
            .withBean(DataSource::class.java, { mockk() })
            .withUserConfiguration(SimbaJdbcAutoConfiguration::class.java)
            .run {
                assertThat(it)
                    .hasSingleBean(SimbaJdbcAutoConfiguration::class.java)
                    .hasSingleBean(JdbcProperties::class.java)
                    .hasSingleBean(MutexOwnerRepository::class.java)
                    .hasSingleBean(MutexContendServiceFactory::class.java)
            }
    }

    @Test
    fun contextLoadsWithCustomProperties() {
        contextRunner
            .withBean(DataSource::class.java, { mockk() })
            .withPropertyValues("simba.jdbc.initial-delay=10S", "simba.jdbc.ttl=20S", "simba.jdbc.transition=30S")
            .withUserConfiguration(SimbaJdbcAutoConfiguration::class.java)
            .run {
                assertThat(it)
                    .hasSingleBean(JdbcProperties::class.java)
                    .hasSingleBean(SimbaJdbcAutoConfiguration::class.java)
                    .hasSingleBean(MutexOwnerRepository::class.java)
                    .hasSingleBean(MutexContendServiceFactory::class.java)
                    .getBean(JdbcProperties::class.java)
                    .extracting { properties ->
                        assertThat(properties.initialDelay).isEqualTo(Duration.ofSeconds(10))
                        assertThat(properties.ttl).isEqualTo(Duration.ofSeconds(20))
                        assertThat(properties.transition).isEqualTo(Duration.ofSeconds(30))
                    }
            }
    }

    @Test
    fun contextLoadsWithDisable() {
        contextRunner
            .withBean(DataSource::class.java, { mockk() })
            .withPropertyValues("simba.jdbc.enabled=false")
            .withUserConfiguration(SimbaJdbcAutoConfiguration::class.java)
            .run {
                assertThat(it)
                    .doesNotHaveBean(JdbcProperties::class.java)
                    .doesNotHaveBean(SimbaJdbcAutoConfiguration::class.java)
                    .doesNotHaveBean(MutexOwnerRepository::class.java)
                    .doesNotHaveBean(MutexContendServiceFactory::class.java)
            }
    }
}
