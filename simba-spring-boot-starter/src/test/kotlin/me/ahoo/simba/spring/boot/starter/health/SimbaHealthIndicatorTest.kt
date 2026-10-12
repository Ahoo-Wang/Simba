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
package me.ahoo.simba.spring.boot.starter.health

import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.AbstractMutexContender
import me.ahoo.simba.core.ContendOutcome
import me.ahoo.simba.core.LeaseConfig
import me.ahoo.simba.core.LeaseContendService
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexLeaseStore
import me.ahoo.simba.core.MutexOwner
import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.Status
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SimbaHealthIndicatorTest {
    private var now = 0L
    private val indicator = SimbaHealthIndicator { now }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    /** ttl + transition = 16s; contention never runs (1h initial delay), events are fed by the test. */
    private val config = LeaseConfig(Duration.ofSeconds(10), Duration.ofSeconds(6), Duration.ofHours(1))

    @AfterEach
    fun shutdown() {
        scheduler.shutdownNow()
    }

    private fun leaseService(mutex: String = "m"): LeaseContendService {
        val store = object : MutexLeaseStore {
            override fun contend(mutex: String, contenderId: String, renew: Boolean, config: LeaseConfig) =
                MutexOwner.NONE

            override fun release(mutex: String, contenderId: String) = true
        }
        return LeaseContendService(object : AbstractMutexContender(mutex) {}, Runnable::run, store, config, scheduler)
            .also {
                it.start()
                indicator.onStarted(it)
            }
    }

    private fun advance(seconds: Long) {
        now += TimeUnit.SECONDS.toNanos(seconds)
    }

    @Suppress("UNCHECKED_CAST")
    private fun Health.mutexes(): List<Map<String, Any>> = details["mutexes"] as List<Map<String, Any>>

    @Test
    fun `no services is healthy`() {
        indicator.health().let {
            it.status.assert().isEqualTo(Status.UP)
            it.mutexes().assert().isEmpty()
        }
    }

    @Test
    fun `contending without owning is healthy`() {
        val service = leaseService()
        advance(5)
        indicator.onContend("m", false, 1, ContendOutcome.OTHER)
        advance(20)

        val health = indicator.health()

        health.status.assert().isEqualTo(Status.UP)
        health.mutexes().single().let {
            it["mutex"].assert().isEqualTo("m")
            it["contenderId"].assert().isEqualTo(service.contenderId)
            it["owner"].assert().isEqualTo(false)
            it["sinceLastSuccess"].assert().isEqualTo("20000ms")
            it["stale"].assert().isEqualTo(false)
        }
        service.stop()
    }

    @Test
    fun `failing backend beyond twice the lease is down`() {
        val service = leaseService()
        indicator.onContend("m", false, 1, ContendOutcome.FAILED)
        advance(32)
        indicator.health().status.assert().isEqualTo(Status.UP)

        advance(1)
        val health = indicator.health()

        health.status.assert().isEqualTo(Status.DOWN)
        health.mutexes().single().let {
            it["failingFor"].assert().isEqualTo("33000ms")
            it["stale"].assert().isEqualTo(true)
        }
        service.stop()
    }

    @Test
    fun `hanging backend without any reply is down`() {
        val service = leaseService()
        advance(33)

        indicator.health().status.assert().isEqualTo(Status.DOWN)
        service.stop()
    }

    @Test
    fun `a successful round trip recovers`() {
        val service = leaseService()
        indicator.onContend("m", false, 1, ContendOutcome.FAILED)
        advance(40)
        indicator.onContend("m", false, 1, ContendOutcome.OWNER)

        val health = indicator.health()

        health.status.assert().isEqualTo(Status.UP)
        health.mutexes().single().containsKey("failingFor").assert().isFalse()
        service.stop()
    }

    @Test
    fun `a recent lease expiry is reported`() {
        val service = leaseService()
        indicator.onContend("m", true, 1, ContendOutcome.OWNER)
        indicator.onLeaseExpired("m")
        advance(3)

        indicator.health().mutexes().single()["leaseExpiredAgo"].assert().isEqualTo("3000ms")

        advance(14)
        indicator.health().mutexes().single().containsKey("leaseExpiredAgo").assert().isFalse()
        service.stop()
    }

    @Test
    fun `a restart measures from the new start`() {
        val service = leaseService()
        indicator.onContend("m", false, 1, ContendOutcome.OTHER)
        advance(100)
        service.stop()
        indicator.onStopped(service)
        service.start()
        indicator.onStarted(service)
        advance(1)

        indicator.health().status.assert().isEqualTo(Status.UP)
        service.stop()
    }

    @Test
    fun `stopped services are forgotten`() {
        val service = leaseService()
        service.stop()
        indicator.onStopped(service)

        indicator.health().mutexes().assert().isEmpty()
    }

    @Test
    fun `services without lease contention report details only`() {
        val service = object : AbstractMutexContendService(object : AbstractMutexContender("zk") {}, Runnable::run) {
            override fun startContend() = Unit
            override fun stopContend() = Unit
            fun lead() = notifyOwner(MutexOwner(contenderId, fencingToken = 5)).join()
        }
        service.start()
        indicator.onStarted(service)
        service.lead()
        advance(1_000)

        val health = indicator.health()

        health.status.assert().isEqualTo(Status.UP)
        health.mutexes().single().let {
            it["owner"].assert().isEqualTo(true)
            it["fencingToken"].assert().isEqualTo(5L)
            it["leaseRemaining"].assert().isEqualTo("unbounded")
            it.containsKey("stale").assert().isFalse()
        }
        (service as MutexContendService).stop()
    }
}
