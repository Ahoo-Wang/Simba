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
 * Runs the annotated bean method on [me.ahoo.simba.Election.schedule] of election [name]: only while this node leads,
 * once per period, interrupted when the term ends. Started after context refresh, stopped on shutdown (work first,
 * then the lease).
 *
 * ```kotlin
 * @SimbaScheduled(name = "report", fixedDelay = "1m")
 * fun generate(term: Term) {
 *     reportService.generate(fencingToken = term.token)
 * }
 * ```
 *
 * The method takes no parameter or a single [me.ahoo.simba.Term], the term it runs in. Set exactly one of
 * [fixedDelay] and [fixedRate]. Every attribute accepts `${...}` placeholders; durations use the Spring Boot formats
 * (`10s`, `500ms`, `PT1M`).
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class SimbaScheduled(
    /** Election (lease name) whose leader runs the method; unique per application. */
    val name: String,
    /** Delay between the end of one run and the start of the next. */
    val fixedDelay: String = "",
    /** Period between the starts of consecutive runs. */
    val fixedRate: String = "",
    /** Delay before the first run of each term. */
    val initialDelay: String = "0s",
)
