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

import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexContender
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ScheduledExecutorService

/**
 * Spring Redis Mutex Contend Service Factory .
 *
 * @author ahoo wang
 */
class SpringRedisMutexContendServiceFactory(
    private val ttl: Duration,
    private val transition: Duration,
    private val redisTemplate: StringRedisTemplate,
    private val listenerContainer: RedisMessageListenerContainer,
    private val handleExecutor: Executor = ForkJoinPool.commonPool(),
    private val scheduledExecutorService: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
) : MutexContendServiceFactory {
    override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
        return SpringRedisMutexContendService(
            mutexContender,
            handleExecutor,
            ttl,
            transition,
            redisTemplate,
            listenerContainer,
            scheduledExecutorService
        )
    }
}
