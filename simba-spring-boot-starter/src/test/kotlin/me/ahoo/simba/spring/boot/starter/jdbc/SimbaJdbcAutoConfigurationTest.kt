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

import io.mockk.every
import io.mockk.mockk
import me.ahoo.simba.jdbc.Dialect
import me.ahoo.simba.jdbc.JdbcLeaseBackend
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.sql.Connection
import javax.sql.DataSource

class SimbaJdbcAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(SimbaJdbcAutoConfiguration::class.java))
        .withPropertyValues("simba.backend=jdbc")

    private fun dataSource(product: String): DataSource {
        val connection = mockk<Connection>(relaxed = true)
        every { connection.metaData.databaseProductName } returns product
        return mockk { every { this@mockk.connection } returns connection }
    }

    @Test
    fun `the dialect property selects the dialect`() {
        contextRunner
            .withBean(DataSource::class.java, { mockk() })
            .withPropertyValues("simba.jdbc.dialect=postgresql")
            .run {
                it.getBean(JdbcLeaseBackend::class.java).dialect.assert().isEqualTo(Dialect.POSTGRESQL)
            }
    }

    @Test
    fun `the dialect is detected from the data source`() {
        contextRunner.withBean(DataSource::class.java, { dataSource("MySQL") }).run {
            it.getBean(JdbcLeaseBackend::class.java).dialect.assert().isEqualTo(Dialect.MYSQL)
        }
        contextRunner.withBean(DataSource::class.java, { dataSource("PostgreSQL") }).run {
            it.getBean(JdbcLeaseBackend::class.java).dialect.assert().isEqualTo(Dialect.POSTGRESQL)
        }
    }

    @Test
    fun `an unknown database fails startup`() {
        contextRunner.withBean(DataSource::class.java, { dataSource("Oracle") }).run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).rootCause().hasMessageContaining("simba.jdbc.dialect")
        }
    }

    @Test
    fun `no data source, or an application lease backend, means no jdbc backend`() {
        contextRunner.run { assertThat(it).doesNotHaveBean(LeaseBackend::class.java) }
        val own = mockk<LeaseBackend>(relaxed = true)
        contextRunner.withBean(DataSource::class.java, { mockk() }).withBean(LeaseBackend::class.java, { own }).run {
            assertThat(it).doesNotHaveBean(JdbcLeaseBackend::class.java)
        }
    }

    @Test
    fun `disabled jdbc backend stays off`() {
        contextRunner
            .withBean(DataSource::class.java, { mockk() })
            .withPropertyValues("simba.jdbc.enabled=false", "simba.jdbc.dialect=mysql")
            .run { assertThat(it).doesNotHaveBean(LeaseBackend::class.java) }
    }
}
