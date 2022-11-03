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

import lombok.SneakyThrows
import lombok.extern.slf4j.Slf4j
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.MutexState
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import java.time.Duration
import java.util.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * RedisMutexContendServiceTest .
 *
 * @author ahoo wang
 */
@Slf4j
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class SpringRedisMutexContendServiceTest {
    var lettuceConnectionFactory: LettuceConnectionFactory? = null
    private var contendServiceFactory: SpringRedisMutexContendServiceFactory? = null
    var listenerContainer: RedisMessageListenerContainer? = null
    @BeforeAll
    fun setup() {
        val redisStandaloneConfiguration = RedisStandaloneConfiguration()
        lettuceConnectionFactory = LettuceConnectionFactory(redisStandaloneConfiguration)
        lettuceConnectionFactory!!.afterPropertiesSet()
        val stringRedisTemplate = StringRedisTemplate(lettuceConnectionFactory!!)
        listenerContainer = RedisMessageListenerContainer()
        listenerContainer!!.setConnectionFactory(lettuceConnectionFactory!!)
        listenerContainer!!.afterPropertiesSet()
        contendServiceFactory = SpringRedisMutexContendServiceFactory(
            Duration.ofSeconds(2),
            Duration.ofSeconds(1),
            stringRedisTemplate,
            listenerContainer!!,
            ForkJoinPool.commonPool(),
            Executors.newScheduledThreadPool(1)
        )
    }

    @SneakyThrows
    @AfterAll
    fun destroy() {
        if (Objects.nonNull(lettuceConnectionFactory)) {
            lettuceConnectionFactory!!.destroy()
        }
        if (Objects.nonNull(listenerContainer)) {
            listenerContainer!!.stop()
        }
    }

    @Test
    fun start() {
        val acquiredFuture: CompletableFuture<MutexState?> = CompletableFuture<Any?>()
        val releasedFuture: CompletableFuture<MutexState?> = CompletableFuture<Any?>()
        val contendService =
            contendServiceFactory!!.createMutexContendService(object : AbstractMutexContender("spring-redis-start") {
                override fun onAcquired(mutexState: MutexState) {
                    SpringRedisMutexContendServiceTest.log.info("onAcquired")
                    acquiredFuture.complete(mutexState)
                }

                override fun onReleased(mutexState: MutexState) {
                    SpringRedisMutexContendServiceTest.log.info("onReleased")
                    releasedFuture.complete(mutexState)
                }
            }) as SpringRedisMutexContendService
        contendService.start()
        acquiredFuture.join()
        Assertions.assertTrue(contendService.isOwner)
        contendService.stop()
        releasedFuture.join()
        Assertions.assertFalse(contendService.isOwner)
    }

    @Test
    fun restart() {
        val acquiredFuture: CompletableFuture<MutexState?> = CompletableFuture<Any?>()
        val releasedFuture: CompletableFuture<MutexState?> = CompletableFuture<Any?>()
        val acquiredFuture2: CompletableFuture<MutexState?> = CompletableFuture<Any?>()
        val releasedFuture2: CompletableFuture<MutexState?> = CompletableFuture<Any?>()
        val contendService =
            contendServiceFactory!!.createMutexContendService(object : AbstractMutexContender("spring-redis-restart") {
                override fun onAcquired(mutexState: MutexState) {
                    SpringRedisMutexContendServiceTest.log.info("onAcquired")
                    if (!acquiredFuture.isDone) {
                        acquiredFuture.complete(mutexState)
                    } else {
                        acquiredFuture2.complete(mutexState)
                    }
                }

                override fun onReleased(mutexState: MutexState) {
                    SpringRedisMutexContendServiceTest.log.info("onReleased")
                    if (!releasedFuture.isDone) {
                        releasedFuture.complete(mutexState)
                    } else {
                        releasedFuture2.complete(mutexState)
                    }
                }
            }) as SpringRedisMutexContendService
        contendService.start()
        acquiredFuture.join()
        Assertions.assertTrue(contendService.isOwner)
        contendService.stop()
        releasedFuture.join()
        Assertions.assertFalse(contendService.isOwner)
        contendService.start()
        acquiredFuture2.join()
        Assertions.assertTrue(contendService.isOwner)
        contendService.stop()
        releasedFuture2.join()
        Assertions.assertFalse(contendService.isOwner)
    }

    @SneakyThrows
    @Test
    fun guard() {
        val acquiredFuture: CompletableFuture<MutexState?> = CompletableFuture<Any?>()
        val releasedFuture: CompletableFuture<MutexState?> = CompletableFuture<Any?>()
        val contendService =
            contendServiceFactory!!.createMutexContendService(object : AbstractMutexContender("spring-redis-guard") {
                override fun onAcquired(mutexState: MutexState) {
                    SpringRedisMutexContendServiceTest.log.info("onAcquired")
                    acquiredFuture.complete(mutexState)
                }

                override fun onReleased(mutexState: MutexState) {
                    SpringRedisMutexContendServiceTest.log.info("onReleased")
                    releasedFuture.complete(mutexState)
                }
            }) as SpringRedisMutexContendService
        contendService.start()
        /*
         **** Very important ****
         */listenerContainer!!.start()
        acquiredFuture.join()
        Assertions.assertTrue(contendService.isOwner)
        TimeUnit.SECONDS.sleep(3)
        Assertions.assertEquals(contendService.afterOwner.ownerId, contendService.contender.contenderId)
        Assertions.assertTrue(contendService.isOwner)
        contendService.stop()
        releasedFuture.join()
        Assertions.assertFalse(contendService.isOwner)
    }

    @Test
    @Throws(InterruptedException::class)
    fun multiContend() {
        val count = AtomicInteger(0)
        val contendServiceList: MutableList<SpringRedisMutexContendService> = ArrayList(10)
        val currentOwnerIdRef = AtomicReference<String>()
        for (i in 0..9) {
            val contendService = contendServiceFactory!!.createMutexContendService(object :
                AbstractMutexContender("spring-redis-multiContend") {
                override fun onAcquired(mutexState: MutexState) {
                    SpringRedisMutexContendServiceTest.log.info("onAcquired")
                    currentOwnerIdRef.set(mutexState.after.ownerId)
                    super.onAcquired(mutexState)
                    Assertions.assertEquals(1, count.incrementAndGet())
                }

                override fun onReleased(mutexState: MutexState) {
                    SpringRedisMutexContendServiceTest.log.info("onReleased")
                    super.onReleased(mutexState)
                    Assertions.assertEquals(0, count.decrementAndGet())
                }
            }) as SpringRedisMutexContendService
            contendService.start()
            /*
             **** Very important ****
             */listenerContainer!!.start()
            contendServiceList.add(contendService)
        }
        TimeUnit.SECONDS.sleep(10)
        Assertions.assertEquals(1, count.get())
        val currentOwnerId = currentOwnerIdRef.get()
        for (contendService in contendServiceList) {
            if (contendService.afterOwner.ownerId != null) {
                Assertions.assertEquals(contendService.afterOwner.ownerId, currentOwnerId)
            }
        }
        Assertions.assertEquals(
            1,
            contendServiceList.stream()
                .filter { contendService: SpringRedisMutexContendService -> contendService.contenderId == currentOwnerId }
                .count())
    }
}
