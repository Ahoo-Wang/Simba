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

import me.ahoo.simba.Simba
import me.ahoo.simba.Term
import me.ahoo.simba.spi.LeaseBackend
import me.ahoo.simba.spring.boot.starter.Leadership
import me.ahoo.simba.spring.boot.starter.SimbaAutoConfiguration
import me.ahoo.simba.testing.InMemoryLeaseBackend
import me.ahoo.test.asserts.assert
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class SimbaSchedulingAutoConfigurationTest {
    private val backend = InMemoryLeaseBackend()

    // All backend modules are on the test classpath; none of their auto-configurations is loaded here.
    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(SimbaAutoConfiguration::class.java, SimbaSchedulingAutoConfiguration::class.java)
        )
        .withPropertyValues("simba.backend=zookeeper")
        .withBean(LeaseBackend::class.java, { backend })

    private fun <T> LinkedBlockingQueue<T>.next(): T =
        checkNotNull(poll(Leadership.WAIT_SECONDS, TimeUnit.SECONDS)) { "timed out" }

    class Report {
        val terms = LinkedBlockingQueue<Term>()

        @SimbaScheduled(name = "\${report.name:report}", fixedDelay = "20ms")
        fun generate(term: Term) {
            terms += term
        }
    }

    class Cleanup {
        val runs = LinkedBlockingQueue<Unit>()

        @SimbaScheduled(name = "cleanup", fixedRate = "20ms", initialDelay = "0s")
        fun clean() {
            runs += Unit
        }
    }

    class Billing {
        val partitions = LinkedBlockingQueue<Pair<Long, Int>>()

        @SimbaPartitioned(group = "billing", partitions = 3, fixedDelay = "20ms")
        fun bill(term: Term, partition: Int) {
            partitions += term.token to partition
        }
    }

    @Test
    fun `a scheduled method runs on the leader with its term`() {
        contextRunner.withBean(Report::class.java).withPropertyValues("report.name=daily-report").run {
            val term = it.getBean(Report::class.java).terms.next()
            term.name.value.assert().isEqualTo("daily-report")
            term.isValid.assert().isTrue()
            backend.owner(term.name)?.token.assert().isEqualTo(term.token)
        }
    }

    @Test
    fun `a method without parameters runs too`() {
        contextRunner.withBean(Cleanup::class.java).run {
            it.getBean(Cleanup::class.java).runs.next()
        }
    }

    @Test
    fun `a partitioned method runs every partition of a single member`() {
        contextRunner.withBean(Billing::class.java).run {
            val seen = HashSet<Int>()
            val queue = it.getBean(Billing::class.java).partitions
            while (seen.size < 3) {
                seen += queue.next().second
            }
            seen.assert().containsExactlyInAnyOrder(0, 1, 2)
        }
    }

    @Test
    fun `closing the context stops the work and releases the leases`() {
        val context = contextRunner.withBean(Report::class.java)
        context.run {
            val term = it.getBean(Report::class.java).terms.next()
            it.close()
            term.isValid.assert().isFalse()
            backend.owner(term.name).assert().isNull()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class LazyReport {
        @Bean
        @Lazy
        fun lazyReport() = Report()
    }

    @Test
    fun `a lazy bean starts once it is created`() {
        contextRunner.withUserConfiguration(LazyReport::class.java).run {
            it.getBean(Report::class.java).terms.next()
        }
    }

    class TwoSchedules {
        @SimbaScheduled(name = "twice", fixedDelay = "1s", fixedRate = "1s")
        fun work() = Unit
    }

    class NoSchedule {
        @SimbaScheduled(name = "never")
        fun work() = Unit
    }

    class WrongParameter {
        @SimbaScheduled(name = "wrong", fixedDelay = "1s")
        fun work(name: String) = name
    }

    class WrongPartitionParameters {
        @SimbaPartitioned(group = "wrong", partitions = 2, fixedDelay = "1s")
        fun work(term: Term) = term
    }

    class NoPartitions {
        @SimbaPartitioned(group = "empty", partitions = 0, fixedDelay = "1s")
        fun work(term: Term, partition: Int) = term to partition
    }

    class NotADuration {
        @SimbaScheduled(name = "bad", fixedDelay = "soon")
        fun work() = Unit
    }

    class ZeroPeriod {
        @SimbaScheduled(name = "zero", fixedDelay = "0s")
        fun work() = Unit
    }

    class Duplicate {
        @SimbaScheduled(name = "same", fixedDelay = "1s")
        fun first() = Unit

        @SimbaScheduled(name = "same", fixedDelay = "1s")
        fun second() = Unit
    }

    @Test
    fun `invalid methods fail startup`() {
        listOf(
            TwoSchedules::class.java to "exactly one of fixedDelay and fixedRate",
            NoSchedule::class.java to "exactly one of fixedDelay and fixedRate",
            WrongParameter::class.java to "no parameter or a single Term",
            WrongPartitionParameters::class.java to "must take (Term, Int)",
            NoPartitions::class.java to "partitions must be positive",
            NotADuration::class.java to "'soon' is not a valid duration",
            ZeroPeriod::class.java to "must be positive",
            Duplicate::class.java to "already used",
        ).forEach { (type, message) ->
            contextRunner.withBean(type).run {
                assertThat(it).hasFailed()
                assertThat(it.startupFailure).rootCause().hasMessageContaining(message)
            }
        }
    }

    @Test
    fun `annotated methods without a simba client fail startup`() {
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SimbaSchedulingAutoConfiguration::class.java))
            .withBean(Report::class.java)
            .run {
                assertThat(it).hasFailed()
                assertThat(it.startupFailure).rootCause().hasMessageContaining("need a Simba bean")
            }
    }

    @Test
    fun `scheduling disabled leaves methods alone`() {
        contextRunner.withBean(Report::class.java).withPropertyValues("simba.scheduling.enabled=false").run {
            assertThat(it).doesNotHaveBean(SimbaScheduledBeanPostProcessor::class.java)
            it.getBean(Simba::class.java).snapshot().elections.assert().isEmpty()
        }
    }
}
