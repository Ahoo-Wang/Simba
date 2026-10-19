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

import me.ahoo.simba.spring.boot.starter.jdbc.JdbcProperties
import me.ahoo.simba.spring.boot.starter.redis.RedisProperties
import me.ahoo.simba.spring.boot.starter.zookeeper.ZookeeperProperties
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * Every property bound with a value other than its default, through the Kotlin primary constructors; this needs
 * `kotlin-reflect`, which the starter declares itself (the example app's tests run without MockK on the classpath).
 */
class PropertiesBindingTest {
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(
        SimbaProperties::class,
        JdbcProperties::class,
        RedisProperties::class,
        ZookeeperProperties::class,
    )
    class Bound

    @Test
    fun `non-default values bind to every property`() {
        ApplicationContextRunner()
            .withUserConfiguration(Bound::class.java)
            .withPropertyValues(
                "simba.enabled=true",
                "simba.backend=redis",
                "simba.session.lease-duration=9s",
                "simba.session.renew-interval=2s",
                "simba.session.drift=0.02",
                "simba.session.margin=70ms",
                "simba.jdbc.enabled=false",
                "simba.jdbc.dialect=postgresql",
                "simba.jdbc.query-timeout=3s",
                "simba.redis.wait-replicas=2",
                "simba.redis.wait-timeout=300ms",
                "simba.redis.route-by-slot=true",
                "simba.zookeeper.connect-string=zk:2181",
                "simba.zookeeper.root=/locks",
                "simba.zookeeper.connection-timeout=4s",
            )
            .run {
                assertThat(it).hasNotFailed()
                it.getBean(SimbaProperties::class.java).assert().isEqualTo(
                    SimbaProperties(
                        backend = "redis",
                        session = SimbaProperties.Session(
                            leaseDuration = Duration.ofSeconds(9),
                            renewInterval = Duration.ofSeconds(2),
                            drift = 0.02,
                            margin = Duration.ofMillis(70),
                        ),
                    )
                )
                it.getBean(JdbcProperties::class.java).assert().isEqualTo(
                    JdbcProperties(false, JdbcProperties.JdbcDialect.POSTGRESQL, Duration.ofSeconds(3))
                )
                it.getBean(RedisProperties::class.java).assert().isEqualTo(
                    RedisProperties(true, 2, Duration.ofMillis(300), true)
                )
                it.getBean(ZookeeperProperties::class.java).assert().isEqualTo(
                    ZookeeperProperties(true, "zk:2181", "/locks", Duration.ofSeconds(4))
                )
            }
    }

    @Test
    fun `unset properties keep their defaults`() {
        ApplicationContextRunner().withUserConfiguration(Bound::class.java).run {
            it.getBean(SimbaProperties::class.java).assert().isEqualTo(SimbaProperties())
            it.getBean(ZookeeperProperties::class.java).assert().isEqualTo(ZookeeperProperties())
        }
    }
}
