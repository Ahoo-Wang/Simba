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

import io.github.oshai.kotlinlogging.KotlinLogging
import me.ahoo.simba.Schedule
import me.ahoo.simba.Simba
import me.ahoo.simba.Term
import org.springframework.aop.framework.AopInfrastructureBean
import org.springframework.aop.framework.AopProxyUtils
import org.springframework.aop.support.AopUtils
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.BeanFactoryAware
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.convert.DurationStyle
import org.springframework.context.EmbeddedValueResolverAware
import org.springframework.context.SmartLifecycle
import org.springframework.core.MethodIntrospector
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.core.annotation.AnnotationUtils
import org.springframework.util.ReflectionUtils
import org.springframework.util.StringValueResolver
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs [SimbaScheduled] and [SimbaPartitioned] methods on the [Simba] bean with the application context: started
 * after refresh, stopped on shutdown before the client closes ([me.ahoo.simba.spring.boot.starter.SimbaLifecycle]
 * runs in a lower phase). Methods of beans created later (lazy beans) start at once while the context runs.
 */
class SimbaScheduledBeanPostProcessor :
    BeanPostProcessor,
    BeanFactoryAware,
    EmbeddedValueResolverAware,
    SmartLifecycle,
    DisposableBean {
    companion object {
        private val log = KotlinLogging.logger {}
    }

    private lateinit var beanFactory: BeanFactory
    private var valueResolver: StringValueResolver? = null
    private val nonAnnotatedClasses: MutableSet<Class<*>> = ConcurrentHashMap.newKeySet()

    private val lock = Any()
    private val pending = mutableListOf<ScheduledMethod>()

    /** Lease names and groups claimed by registered methods, with the method claiming them. */
    private val leases = mutableMapOf<String, String>()
    private val groups = mutableMapOf<String, String>()
    private val running = mutableListOf<RunningMethod>()

    @Volatile
    private var started = false

    override fun setBeanFactory(beanFactory: BeanFactory) {
        this.beanFactory = beanFactory
    }

    override fun setEmbeddedValueResolver(resolver: StringValueResolver) {
        valueResolver = resolver
    }

    override fun postProcessAfterInitialization(bean: Any, beanName: String): Any {
        if (bean is AopInfrastructureBean) {
            return bean
        }
        val targetClass = AopProxyUtils.ultimateTargetClass(bean)
        if (targetClass in nonAnnotatedClasses) {
            return bean
        }
        val scheduled = annotated(targetClass, SimbaScheduled::class.java)
        val partitioned = annotated(targetClass, SimbaPartitioned::class.java)
        if (scheduled.isEmpty() && partitioned.isEmpty()) {
            nonAnnotatedClasses.add(targetClass)
            return bean
        }
        scheduled.forEach { (method, annotation) -> register(scheduledMethod(bean, beanName, method, annotation)) }
        partitioned.forEach { (method, annotation) -> register(partitionedMethod(bean, beanName, method, annotation)) }
        return bean
    }

    private fun <A : Annotation> annotated(targetClass: Class<*>, type: Class<A>): Map<Method, A> {
        if (!AnnotationUtils.isCandidateClass(targetClass, type)) {
            return emptyMap()
        }
        return MethodIntrospector.selectMethods(
            targetClass,
            MethodIntrospector.MetadataLookup { method -> AnnotatedElementUtils.findMergedAnnotation(method, type) }
        )
    }

    private fun scheduledMethod(bean: Any, beanName: String, method: Method, annotation: SimbaScheduled): ScheduledMethod {
        val where = "@SimbaScheduled method [$beanName.${method.name}]"
        val takesTerm = when {
            method.parameterCount == 0 -> false
            method.parameterCount == 1 && method.parameterTypes[0].isAssignableFrom(Term::class.java) -> true
            else -> throw IllegalStateException("$where must take no parameter or a single Term.")
        }
        val name = resolve(annotation.name)
        check(name.isNotBlank()) { "$where must set a name." }
        val schedule = schedule(where, annotation.fixedDelay, annotation.fixedRate, annotation.initialDelay)
        val invoke = invoker(bean, method)
        return ScheduledMethod(where, setOf(name)) { simba ->
            val task = simba.election(name).schedule(schedule) { term ->
                if (takesTerm) invoke(arrayOf(term)) else invoke(emptyArray())
            }
            task::stop
        }
    }

    private fun partitionedMethod(
        bean: Any,
        beanName: String,
        method: Method,
        annotation: SimbaPartitioned,
    ): ScheduledMethod {
        val where = "@SimbaPartitioned method [$beanName.${method.name}]"
        val types = method.parameterTypes
        check(
            types.size == 2 && types[0].isAssignableFrom(Term::class.java) &&
                (types[1] == Int::class.javaPrimitiveType || types[1] == Int::class.javaObjectType)
        ) { "$where must take (Term, Int)." }
        val group = resolve(annotation.group)
        check(group.isNotBlank()) { "$where must set a group." }
        check(annotation.partitions > 0) { "$where partitions must be positive." }
        val schedule = schedule(where, annotation.fixedDelay, annotation.fixedRate, annotation.initialDelay)
        val invoke = invoker(bean, method)
        val leases = (0 until annotation.partitions).mapTo(LinkedHashSet()) { "$group.$it" }
        return ScheduledMethod(where, leases, group) { simba ->
            val task = simba.group(group, annotation.partitions).schedule(schedule) { term, partition ->
                invoke(arrayOf(term, partition))
            }
            task::stop
        }
    }

    private fun schedule(where: String, fixedDelay: String, fixedRate: String, initialDelay: String): Schedule {
        val delay = resolve(fixedDelay)
        val rate = resolve(fixedRate)
        check(delay.isEmpty() != rate.isEmpty()) { "$where must set exactly one of fixedDelay and fixedRate." }
        val initial = duration(where, "initialDelay", initialDelay)
        check(!initial.isNegative) { "$where initialDelay must not be negative." }
        return if (delay.isNotEmpty()) {
            Schedule.fixedDelay(period(where, "fixedDelay", delay), initial)
        } else {
            Schedule.fixedRate(period(where, "fixedRate", rate), initial)
        }
    }

    private fun invoker(bean: Any, method: Method): (Array<Any>) -> Unit {
        val invocable = AopUtils.selectInvocableMethod(method, bean.javaClass)
        ReflectionUtils.makeAccessible(invocable)
        return { args ->
            try {
                invocable.invoke(bean, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    private fun resolve(value: String): String = valueResolver?.resolveStringValue(value) ?: value

    private fun duration(where: String, attribute: String, value: String): Duration {
        val resolved = resolve(value)
        return try {
            DurationStyle.detectAndParse(resolved)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("$where $attribute [$resolved] is not a duration.", e)
        }
    }

    private fun period(where: String, attribute: String, value: String): Duration {
        val period = duration(where, attribute, value)
        check(!period.isNegative && !period.isZero) { "$where $attribute must be positive." }
        return period
    }

    private fun register(method: ScheduledMethod) {
        synchronized(lock) {
            method.group?.let { group ->
                groups[group]?.let { other -> error("${method.where}: group [$group] is already used by $other.") }
            }
            method.leases.firstOrNull { it in leases }?.let { lease ->
                error("${method.where}: election [$lease] is already used by ${leases[lease]}.")
            }
            method.group?.let { groups[it] = method.where }
            method.leases.forEach { leases[it] = method.where }
            if (started) {
                running += method.start(simba())
            } else {
                pending += method
            }
        }
    }

    private fun simba(): Simba =
        checkNotNull(beanFactory.getBeanProvider(Simba::class.java).ifAvailable) {
            "@SimbaScheduled and @SimbaPartitioned need a Simba bean: add a Simba backend (jdbc, redis or zookeeper)."
        }

    override fun start() {
        synchronized(lock) {
            if (started) {
                return
            }
            if (pending.isNotEmpty()) {
                val simba = simba()
                pending.forEach { running += it.start(simba) }
                pending.clear()
            }
            started = true
        }
    }

    /** Stops every method's election or group and waits until its work exited and its leases were released. */
    override fun stop() {
        val stops = synchronized(lock) {
            started = false
            running.map { it.stop() }.also { running.clear() }
        }
        stops.forEach { (where, stop) ->
            try {
                stop.toCompletableFuture().join()
            } catch (e: RuntimeException) {
                log.warn(e) { "stop - $where - failed:[${e.message}]." }
            }
        }
    }

    override fun isRunning(): Boolean = started

    /** Stops methods left running when the context fails before the lifecycle stop phase. */
    override fun destroy() {
        stop()
    }

    /**
     * One annotated method: the lease names it elects ([leases], `{group}.{partition}` for a group) and its [group]
     * are unique per application.
     */
    private class ScheduledMethod(
        val where: String,
        val leases: Set<String>,
        val group: String? = null,
        private val run: (Simba) -> () -> CompletionStage<Void>,
    ) {
        fun start(simba: Simba): RunningMethod = RunningMethod(where, run(simba))
    }

    private class RunningMethod(val where: String, private val stopTask: () -> CompletionStage<Void>) {
        fun stop(): Pair<String, CompletionStage<Void>> =
            try {
                where to stopTask()
            } catch (e: IllegalStateException) {
                where to CompletableFuture.failedFuture(e)
            }
    }
}
