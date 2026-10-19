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
 * Runs the annotated bean method on [me.ahoo.simba.partition.PartitionGroup.schedule] of group [group]: every
 * partition in `0 until partitions` runs on exactly one live member at a time (rendezvous assignment over the
 * members' sessions), elected as `"{group}.{partition}"`.
 *
 * ```kotlin
 * @SimbaPartitioned(group = "billing", partitions = 16, fixedDelay = "1m")
 * fun bill(term: Term, partition: Int) {
 *     billing.run(shard = partition, fencingToken = term.token)
 * }
 * ```
 *
 * The method takes `(Term, Int)`: the term of the partition's election and the partition. Set exactly one of
 * [fixedDelay] and [fixedRate]; string attributes accept `${...}` placeholders.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@MustBeDocumented
annotation class SimbaPartitioned(
    /** Group name, unique per application; partition `p` is elected as `"{group}.{p}"`. */
    val group: String,
    /** Number of partitions; every member of the group must use the same value. */
    val partitions: Int,
    /** Delay between the end of one run and the start of the next, per partition. */
    val fixedDelay: String = "",
    /** Period between the starts of consecutive runs, per partition. */
    val fixedRate: String = "",
    /** Delay before the first run of each partition term. */
    val initialDelay: String = "0s",
)
