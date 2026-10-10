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
package me.ahoo.simba.spring.boot.starter.scheduling

import me.ahoo.simba.core.AbstractMutexContendService
import me.ahoo.simba.core.MutexContendService
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.core.MutexContender
import me.ahoo.simba.core.MutexOwner
import me.ahoo.simba.schedule.ScheduleConfig
import me.ahoo.simba.schedule.ScheduleContext
import me.ahoo.simba.schedule.SimbaScheduler
import me.ahoo.simba.spring.boot.starter.SimbaAutoConfiguration
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SimbaSchedulingAutoConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SimbaAutoConfiguration::class.java, SimbaSchedulingAutoConfiguration::class.java)
        )
        // All backend modules are on the test classpath; none of their auto-configurations is loaded here.
        .withPropertyValues("simba.backend=zookeeper")

    private fun withLeaderFactory(factory: LeaderFactory = LeaderFactory()) =
        contextRunner.withBean(MutexContendServiceFactory::class.java, { factory })

    @Test
    fun `annotated methods run on the leader with the context and stop on close`() {
        val factory = LeaderFactory()
        withLeaderFactory(factory)
            .withUserConfiguration(Jobs::class.java)
            .run {
                val jobs = it.getBean(Jobs::class.java)
                jobs.runs.poll(2, TimeUnit.SECONDS).assert().isEqualTo("plain")
                jobs.contexts.poll(2, TimeUnit.SECONDS).assert().isEqualTo("with-context" to 42L)
                it.getBean(SimbaScheduledBeanPostProcessor::class.java).isRunning.assert().isTrue()
                factory.services.map { service -> service.mutex }.assert()
                    .containsExactlyInAnyOrder("plain", "with-context")
            }
        factory.services.forEach { it.running.assert().isFalse() }
    }

    @Test
    fun `attributes resolve placeholders`() {
        val factory = LeaderFactory()
        withLeaderFactory(factory)
            .withPropertyValues("job.mutex=resolved", "job.rate=PT0.05S")
            .withUserConfiguration(PlaceholderJob::class.java)
            .run {
                it.getBean(PlaceholderJob::class.java).runs.poll(2, TimeUnit.SECONDS).assert()
                    .startsWith("resolved-worker-")
                factory.services.single().mutex.assert().isEqualTo("resolved")
            }
    }

    @Test
    fun `SimbaScheduler beans run with the context`() {
        withLeaderFactory()
            .withUserConfiguration(SchedulerBeanConfiguration::class.java)
            .run {
                it.getBean(SchedulerBeanConfiguration::class.java).runs.poll(2, TimeUnit.SECONDS).assert()
                    .isEqualTo("bean")
                it.getBean(SimbaScheduler::class.java).isLeader.assert().isTrue()
            }
    }

    @Test
    fun `lazy beans start as soon as they are created`() {
        withLeaderFactory()
            .withUserConfiguration(LazyJobConfiguration::class.java)
            .run {
                val job = it.getBean(LazyJob::class.java)
                job.runs.poll(2, TimeUnit.SECONDS).assert().isEqualTo("lazy")
            }
    }

    @Test
    fun `scheduling can be disabled`() {
        withLeaderFactory()
            .withPropertyValues("simba.scheduling.enabled=false")
            .withUserConfiguration(Jobs::class.java)
            .run {
                assertThat(it).doesNotHaveBean(SimbaScheduledBeanPostProcessor::class.java)
                it.getBean(Jobs::class.java).runs.poll(200, TimeUnit.MILLISECONDS).assert().isNull()
            }
    }

    @Test
    fun `annotated methods without a backend fail startup`() {
        contextRunner.withUserConfiguration(Jobs::class.java).run {
            assertThat(it).hasFailed()
            assertThat(it.startupFailure).hasStackTraceContaining("needs a MutexContendServiceFactory")
        }
    }

    @Test
    fun `invalid declarations fail startup`() {
        mapOf(
            BothPeriods::class.java to "exactly one of fixedDelay and fixedRate",
            NoPeriod::class.java to "exactly one of fixedDelay and fixedRate",
            ZeroPeriod::class.java to "fixedDelay must be positive",
            BadDuration::class.java to "fixedRate [often] is not a duration",
            BadParameter::class.java to "must take no parameter or a single ScheduleContext",
            DuplicateMutex::class.java to "mutex [same] is already used"
        ).forEach { (configuration, message) ->
            withLeaderFactory().withUserConfiguration(configuration).run {
                assertThat(it).hasFailed()
                assertThat(it.startupFailure).hasStackTraceContaining(message)
            }
        }
    }

    class Jobs {
        val runs = LinkedBlockingQueue<String>()
        val contexts = LinkedBlockingQueue<Pair<String, Long>>()

        @SimbaScheduled(mutex = "plain", fixedDelay = "1h")
        fun plain() {
            runs.add("plain")
        }

        @SimbaScheduled(mutex = "with-context", fixedRate = "1h", initialDelay = "0ms")
        fun withContext(context: ScheduleContext) {
            contexts.add(context.mutex to context.fencingToken)
        }
    }

    class PlaceholderJob {
        val runs = LinkedBlockingQueue<String>()

        @SimbaScheduled(mutex = "\${job.mutex}", fixedRate = "\${job.rate}", worker = "\${job.mutex}-worker")
        fun run() {
            runs.add(Thread.currentThread().name)
        }
    }

    @Configuration(proxyBeanMethods = false)
    class LazyJobConfiguration {
        @Bean
        @Lazy
        fun lazyJob() = LazyJob()
    }

    class LazyJob {
        val runs = LinkedBlockingQueue<String>()

        @SimbaScheduled(mutex = "lazy", fixedDelay = "1h")
        fun run() {
            runs.add("lazy")
        }
    }

    class BothPeriods {
        @SimbaScheduled(mutex = "both", fixedDelay = "1s", fixedRate = "1s")
        fun run() = Unit
    }

    class NoPeriod {
        @SimbaScheduled(mutex = "none")
        fun run() = Unit
    }

    class ZeroPeriod {
        @SimbaScheduled(mutex = "zero", fixedDelay = "0s")
        fun run() = Unit
    }

    class BadDuration {
        @SimbaScheduled(mutex = "bad", fixedRate = "often")
        fun run() = Unit
    }

    class BadParameter {
        @SimbaScheduled(mutex = "param", fixedDelay = "1s")
        fun run(@Suppress("UNUSED_PARAMETER") name: String) = Unit
    }

    class DuplicateMutex {
        @SimbaScheduled(mutex = "same", fixedDelay = "1s")
        fun first() = Unit

        @SimbaScheduled(mutex = "same", fixedDelay = "1s")
        fun second() = Unit
    }

    @Configuration(proxyBeanMethods = false)
    class SchedulerBeanConfiguration {
        val runs = LinkedBlockingQueue<String>()

        @Bean
        fun beanScheduler(factory: MutexContendServiceFactory): SimbaScheduler {
            return SimbaScheduler("bean", factory, ScheduleConfig.delay(Duration.ZERO, Duration.ofHours(1))) {
                runs.add("bean")
            }
        }
    }

    /**
     * Grants leadership on start with fencing token 42.
     */
    class LeaderFactory : MutexContendServiceFactory {
        val services = CopyOnWriteArrayList<MutexContendService>()

        override fun createMutexContendService(mutexContender: MutexContender): MutexContendService {
            return LeaderService(mutexContender).also { services.add(it) }
        }
    }

    private class LeaderService(contender: MutexContender) :
        AbstractMutexContendService(contender, Executor { it.run() }) {
        override fun startContend() {
            notifyOwner(MutexOwner(contenderId, fencingToken = 42))
        }

        override fun stopContend() = Unit
    }
}
