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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class ConfigurationMetadataTest {
    @Test
    fun `published metadata lists every supported property`() {
        val metadata = checkNotNull(
            javaClass.classLoader.getResourceAsStream("META-INF/spring-configuration-metadata.json")
        ).bufferedReader().use { it.readText() }
        val properties = metadata.substringAfter("\"properties\": [").substringBefore("\"hints\"")
        val items = PROPERTY.findAll(properties).associate { it.groupValues[1] to it.value }
            .filterKeys { it.startsWith("simba.") }

        items.keys.assert().containsExactlyInAnyOrderElementsOf(EXPECTED.map { it.first })
        EXPECTED.forEach { (name, type, defaultValue) ->
            val item = items.getValue(name)
            item.assert().contains("\"type\": \"$type\"")
            if (defaultValue == null) {
                item.assert().doesNotContain("\"defaultValue\"")
            } else {
                item.assert().contains("\"defaultValue\": $defaultValue")
            }
        }
    }

    companion object {
        private val PROPERTY = Regex("""\{[^{}]*"name"\s*:\s*"([^"]+)"[^{}]*}""")
        private const val BOOLEAN = "java.lang.Boolean"
        private const val DURATION = "java.time.Duration"
        private val EXPECTED: List<Triple<String, String, String?>> = listOf(
            Triple("simba.enabled", BOOLEAN, "true"),
            Triple("simba.backend", "java.lang.String", null),
            Triple("simba.session.lease-duration", DURATION, "\"15s\""),
            Triple("simba.session.renew-interval", DURATION, null),
            Triple("simba.session.drift", "java.lang.Double", "0.01"),
            Triple("simba.session.margin", DURATION, "\"50ms\""),
            Triple("simba.jdbc.enabled", BOOLEAN, "true"),
            Triple(
                "simba.jdbc.dialect",
                "me.ahoo.simba.spring.boot.starter.jdbc.JdbcProperties\$JdbcDialect",
                null
            ),
            Triple("simba.jdbc.query-timeout", DURATION, null),
            Triple("simba.redis.enabled", BOOLEAN, "true"),
            Triple("simba.redis.wait-replicas", "java.lang.Integer", "0"),
            Triple("simba.redis.wait-timeout", DURATION, "\"1s\""),
            Triple("simba.redis.route-by-slot", BOOLEAN, null),
            Triple("simba.zookeeper.enabled", BOOLEAN, "true"),
            Triple("simba.zookeeper.connect-string", "java.lang.String", "\"localhost:2181\""),
            // The processor escapes the slash.
            Triple("simba.zookeeper.root", "java.lang.String", "\"\\/simba\""),
            Triple("simba.zookeeper.connection-timeout", DURATION, "\"15s\""),
            Triple("simba.zookeeper.auth.scheme", "java.lang.String", "\"digest\""),
            Triple("simba.zookeeper.auth.credentials", "java.lang.String", null),
            Triple(
                "simba.zookeeper.acl",
                "me.ahoo.simba.spring.boot.starter.zookeeper.ZookeeperProperties\$Acl",
                "\"auto\""
            ),
            Triple("simba.scheduling.enabled", BOOLEAN, "true"),
            Triple("simba.metrics.enabled", BOOLEAN, "true"),
        )
    }
}
