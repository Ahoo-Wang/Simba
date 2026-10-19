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

package me.ahoo.simba.spring.boot.starter.endpoint

import me.ahoo.simba.Simba
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spring.boot.starter.Leadership
import me.ahoo.simba.spring.boot.starter.SimbaAutoConfiguration
import me.ahoo.simba.testing.InMemoryLeaseBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import tools.jackson.databind.json.JsonMapper

class SimbaEndpointAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SimbaAutoConfiguration::class.java, SimbaEndpointAutoConfiguration::class.java)
        )
        .withPropertyValues("simba.backend=zookeeper")
        .withBean(LeaseBackend::class.java, { InMemoryLeaseBackend() })

    @Test
    fun `endpoint is absent unless exposed`() {
        contextRunner.run { assertThat(it).hasNotFailed().doesNotHaveBean(SimbaEndpoint::class.java) }
    }

    @Test
    fun `endpoint reports the session and every election`() {
        contextRunner.withPropertyValues("management.endpoints.web.exposure.include=simba").run {
            val endpoint = it.getBean(SimbaEndpoint::class.java)
            val leadership = Leadership()
            it.getBean(Simba::class.java).election("endpoint").start(leadership)
            val term = leadership.nextTerm()

            val snapshot = endpoint.snapshot()
            snapshot.session.status.assert().isEqualTo("LIVE")
            snapshot.session.id.assert().isNotNull()
            val election = snapshot.elections.single()
            election.name.assert().isEqualTo("endpoint")
            election.status.assert().isEqualTo("LEADER")
            election.kind.assert().isEqualTo("ELECTION")
            election.token.assert().isEqualTo(term.token)
            (election.remainingMillis > 0).assert().isTrue()
            endpoint.election("endpoint")?.token.assert().isEqualTo(term.token)
            endpoint.election("unknown").assert().isNull()
            JsonMapper.builder().build().writeValueAsString(snapshot).assert().contains("\"status\":\"LEADER\"")
        }
    }
}
