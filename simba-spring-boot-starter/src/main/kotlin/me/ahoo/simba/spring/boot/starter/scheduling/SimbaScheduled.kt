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

/**
 * Runs the annotated bean method on the leader of [mutex] only, as a [me.ahoo.simba.schedule.SimbaScheduler]
 * started after context refresh and stopped on shutdown.
 *
 * ```kotlin
 * @SimbaScheduled(mutex = "report", fixedDelay = "1m")
 * fun generate(context: ScheduleContext) {
 *     reportService.generate(fencingToken = context.fencingToken)
 * }
 * ```
 *
 * The method takes no parameter or a single [me.ahoo.simba.schedule.ScheduleContext]. Set exactly one of
 * [fixedDelay] and [fixedRate]. Every attribute accepts `${...}` placeholders; durations use the Spring Boot
 * formats (`10s`, `500ms`, `PT1M`). Work is interrupted when the node loses leadership.
 *
 * @author ahoo wang
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class SimbaScheduled(
    /**
     * Mutex whose leader runs the method; unique per application.
     */
    val mutex: String,
    /**
     * Delay between the end of one run and the start of the next.
     */
    val fixedDelay: String = "",
    /**
     * Period between the starts of consecutive runs.
     */
    val fixedRate: String = "",
    /**
     * Delay before the first run after this node becomes leader.
     */
    val initialDelay: String = "0s",
    /**
     * Thread name prefix of the work executor; defaults to [mutex].
     */
    val worker: String = ""
)
