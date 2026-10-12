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

import me.ahoo.simba.SimbaException
import me.ahoo.simba.partition.MemberRegistry
import me.ahoo.simba.test.MemberRegistrySpec
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class JdbcMemberRegistryTest : MemberRegistrySpec() {
    private val dataSource = MySqlFixture.newDataSource()
    override val memberRegistry: MemberRegistry = JdbcMemberRegistry(dataSource, Duration.ofSeconds(5))

    @Test
    fun `invalid durations are rejected`() {
        assertThrows<IllegalArgumentException> { memberRegistry.heartbeat("g", "m", Duration.ZERO) }
        assertThrows<IllegalArgumentException> { JdbcMemberRegistry(dataSource, Duration.ofMillis(-1)) }
        // A fractional timeout rounds up to whole seconds rather than to "no timeout".
        JdbcMemberRegistry(dataSource, Duration.ofMillis(1500)).members("g").assert().isEmpty()
    }

    @Test
    fun `database errors surface as SimbaException`() {
        val closed = MySqlFixture.newDataSource().also { it.close() }

        assertThrows<SimbaException> { JdbcMemberRegistry(closed).members("g") }
    }

    @AfterAll
    fun close() {
        dataSource.close()
    }
}
