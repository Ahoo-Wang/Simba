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

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test
import java.time.Duration

/**
 * RedisPropertiesTest .
 *
 * @author ahoo wang
 */
internal class RedisPropertiesTest {
    @Test
    fun defaultProperties() {
        val properties = RedisProperties()
        assertThat(properties.enabled, equalTo(true))
        assertThat(properties.ttl, equalTo(Duration.ofSeconds(10)))
        assertThat(properties.transition, equalTo(Duration.ofSeconds(6)))
    }

    @Test
    fun setEnabled() {
        val properties = RedisProperties(false)
        assertThat(properties.enabled, equalTo(false))
    }

    @Test
    fun setTtl() {
        val ttl = Duration.ofSeconds(20)
        val properties = RedisProperties(ttl = ttl)
        assertThat(properties.ttl, equalTo(ttl))
    }

    @Test
    fun setTransition() {
        val transition = Duration.ofSeconds(8)
        val properties = RedisProperties(transition = transition)
        assertThat(properties.transition, equalTo(transition))
    }
}
