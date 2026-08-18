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

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test

class ConfigurationMetadataTest {
    @Test
    fun `published metadata lists every supported property`() {
        val metadata = checkNotNull(
            javaClass.classLoader.getResourceAsStream("META-INF/spring-configuration-metadata.json")
        ).bufferedReader().use { it.readText() }
        val properties = metadata.substringAfter("\"properties\": [").substringBefore("\"hints\"")
        val propertyItems = PROPERTY.findAll(properties).associate { it.groupValues[1] to it.value }

        assertThat(
            propertyItems.keys,
            containsInAnyOrder(*EXPECTED_PROPERTIES.map { it.first }.toTypedArray())
        )
        EXPECTED_PROPERTIES.forEach { (name, type, defaultValue) ->
            assertThat(
                propertyItems.getValue(name),
                allOf(
                    containsString("\"type\": \"$type\""),
                    containsString("\"defaultValue\": $defaultValue")
                )
            )
        }
    }

    companion object {
        private val PROPERTY = Regex("""\{[^{}]*"name"\s*:\s*"([^"]+)"[^{}]*}""")
        private val EXPECTED_PROPERTIES = listOf(
            Triple("simba.enabled", "java.lang.Boolean", "true"),
            Triple("simba.jdbc.enabled", "java.lang.Boolean", "true"),
            Triple("simba.jdbc.initial-delay", "java.time.Duration", "\"0s\""),
            Triple("simba.jdbc.ttl", "java.time.Duration", "\"10s\""),
            Triple("simba.jdbc.transition", "java.time.Duration", "\"6s\""),
            Triple("simba.redis.enabled", "java.lang.Boolean", "true"),
            Triple("simba.redis.ttl", "java.time.Duration", "\"10s\""),
            Triple("simba.redis.transition", "java.time.Duration", "\"6s\""),
            Triple("simba.zookeeper.enabled", "java.lang.Boolean", "true")
        )
    }
}
