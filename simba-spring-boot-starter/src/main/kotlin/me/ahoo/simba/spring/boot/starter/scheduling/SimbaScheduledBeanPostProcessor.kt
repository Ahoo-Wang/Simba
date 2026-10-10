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
import me.ahoo.simba.core.MutexContendServiceFactory
import me.ahoo.simba.schedule.ScheduleConfig
import me.ahoo.simba.schedule.ScheduleContext
import me.ahoo.simba.schedule.ScheduledWork
import me.ahoo.simba.schedule.SimbaScheduler
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns [SimbaScheduled] methods into [SimbaScheduler]s and runs them, together with `SimbaScheduler` beans, with
 * the application context: started after refresh, stopped on shutdown (before the backend executors close).
 * Schedulers registered later (lazy beans) start immediately while the context runs.
 *
 * @author ahoo wang
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
    private val pendingMethods = mutableListOf<ScheduledMethod>()
    private val annotatedMutexes = mutableSetOf<String>()
    private val schedulers = mutableListOf<SimbaScheduler>()

    @Volatile
    private var running = false

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
        if (bean is SimbaScheduler) {
            manage(bean)
            return bean
        }
        val targetClass = AopProxyUtils.ultimateTargetClass(bean)
        if (targetClass in nonAnnotatedClasses ||
            !AnnotationUtils.isCandidateClass(targetClass, SimbaScheduled::class.java)
        ) {
            return bean
        }
        val annotated = MethodIntrospector.selectMethods(
            targetClass,
            MethodIntrospector.MetadataLookup { method ->
                AnnotatedElementUtils.findMergedAnnotation(method, SimbaScheduled::class.java)
            }
        )
        if (annotated.isEmpty()) {
            nonAnnotatedClasses.add(targetClass)
            return bean
        }
        annotated.forEach { (method, annotation) ->
            register(scheduledMethod(bean, beanName, method, annotation))
        }
        return bean
    }

    private fun scheduledMethod(
        bean: Any,
        beanName: String,
        method: Method,
        annotation: SimbaScheduled
    ): ScheduledMethod {
        val where = "@SimbaScheduled method [$beanName.${method.name}]"
        val takesContext = when {
            method.parameterCount == 0 -> false
            method.parameterCount == 1 && method.parameterTypes[0].isAssignableFrom(ScheduleContext::class.java) ->
                true

            else -> throw IllegalStateException("$where must take no parameter or a single ScheduleContext.")
        }
        val mutex = resolve(annotation.mutex)
        check(mutex.isNotBlank()) { "$where must set a mutex." }
        val fixedDelay = resolve(annotation.fixedDelay)
        val fixedRate = resolve(annotation.fixedRate)
        check(fixedDelay.isEmpty() != fixedRate.isEmpty()) {
            "$where must set exactly one of fixedDelay and fixedRate."
        }
        val initialDelay = duration(where, "initialDelay", annotation.initialDelay)
        check(!initialDelay.isNegative) { "$where initialDelay must not be negative." }
        val config = if (fixedDelay.isNotEmpty()) {
            ScheduleConfig.delay(initialDelay, period(where, "fixedDelay", fixedDelay))
        } else {
            ScheduleConfig.rate(initialDelay, period(where, "fixedRate", fixedRate))
        }
        val invocable = AopUtils.selectInvocableMethod(method, bean.javaClass)
        ReflectionUtils.makeAccessible(invocable)
        val work = ScheduledWork { context ->
            try {
                if (takesContext) invocable.invoke(bean, context) else invocable.invoke(bean)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        return ScheduledMethod(where, mutex, config, resolve(annotation.worker).ifBlank { mutex }, work)
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
            check(annotatedMutexes.add(method.mutex)) {
                "${method.where}: mutex [${method.mutex}] is already used by another @SimbaScheduled method."
            }
            if (running) {
                start(materialize(method, contendServiceFactory()))
            } else {
                pendingMethods.add(method)
            }
        }
    }

    private fun manage(scheduler: SimbaScheduler) {
        synchronized(lock) {
            schedulers.add(scheduler)
            if (running) {
                start(scheduler)
            }
        }
    }

    private fun materialize(method: ScheduledMethod, factory: MutexContendServiceFactory): SimbaScheduler {
        return SimbaScheduler(method.mutex, factory, method.config, method.worker, method.work).also {
            schedulers.add(it)
        }
    }

    private fun contendServiceFactory(): MutexContendServiceFactory {
        return checkNotNull(beanFactory.getBeanProvider(MutexContendServiceFactory::class.java).ifAvailable) {
            "@SimbaScheduled needs a MutexContendServiceFactory: add a Simba backend (jdbc, redis or zookeeper)."
        }
    }

    private fun start(scheduler: SimbaScheduler) {
        if (!scheduler.running) {
            scheduler.start()
        }
    }

    override fun start() {
        synchronized(lock) {
            if (running) {
                return
            }
            if (pendingMethods.isNotEmpty()) {
                val factory = contendServiceFactory()
                pendingMethods.forEach { materialize(it, factory) }
                pendingMethods.clear()
            }
            schedulers.forEach(::start)
            running = true
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override fun stop() {
        synchronized(lock) {
            running = false
            schedulers.forEach { scheduler ->
                try {
                    if (scheduler.running) {
                        scheduler.stop()
                    }
                } catch (e: RuntimeException) {
                    log.warn(e) { "stop - mutex:[${scheduler.mutex}] - failed:[${e.message}]." }
                }
            }
        }
    }

    override fun isRunning(): Boolean = running

    /**
     * Stops schedulers left running when the context fails before the lifecycle stop phase; a stopped scheduler
     * holds no threads.
     */
    override fun destroy() {
        stop()
    }

    private class ScheduledMethod(
        val where: String,
        val mutex: String,
        val config: ScheduleConfig,
        val worker: String,
        val work: ScheduledWork
    )
}
