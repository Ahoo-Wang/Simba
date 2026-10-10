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
package me.ahoo.simba.spring.redis

import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.testcontainers.containers.GenericContainer

/**
 * Redis for the Redis tests: one container per test JVM. Requires Docker.
 */
object RedisFixture {
    private const val IMAGE = "redis:7.4-alpine"
    private const val PORT = 6379

    private val container: GenericContainer<*> by lazy {
        GenericContainer(IMAGE).withExposedPorts(PORT).also { it.start() }
    }

    fun newConnectionFactory(): LettuceConnectionFactory {
        val configuration = RedisStandaloneConfiguration(container.host, container.getMappedPort(PORT))
        return LettuceConnectionFactory(configuration).also { it.afterPropertiesSet() }
    }
}
