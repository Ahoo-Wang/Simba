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
package me.ahoo.simba.partition

import me.ahoo.simba.schedule.ScheduleContext

/**
 * What partitioned work knows about the partition and the ownership term it runs in.
 *
 * @author ahoo wang
 */
interface PartitionContext : ScheduleContext {
    val group: String

    /**
     * The partition, in `0 until partitions`.
     */
    val partition: Int
    val partitions: Int
}

/**
 * Work that runs, per partition, only on the node owning that partition.
 *
 * @author ahoo wang
 */
fun interface PartitionedWork {
    fun work(context: PartitionContext)
}
