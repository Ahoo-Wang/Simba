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
package me.ahoo.simba.core

import com.google.common.base.Strings
import me.ahoo.simba.SimbaException
import me.ahoo.simba.util.ProcessId.currentProcessId
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong

/**
 * Contender Id Generator.
 *
 * @author ahoo wang
 */
interface ContenderIdGenerator {
    fun generate(): String

    companion object {
        val UUID = UUIDContenderIdGenerator
        val HOST = HostContenderIdGenerator
    }
}


object UUIDContenderIdGenerator : ContenderIdGenerator {
    override fun generate(): String {
        return java.util.UUID.randomUUID().toString().replace("-", "")
    }
}

object HostContenderIdGenerator : ContenderIdGenerator {
    private val counter = AtomicLong()
    override fun generate(): String {
        return try {
            val localHost = InetAddress.getLocalHost()
            val processId = currentProcessId
            val seq = counter.getAndIncrement()
            Strings.lenientFormat("%s:%s@%s", seq, processId, localHost.hostAddress)
        } catch (unknownHostException: UnknownHostException) {
            throw SimbaException(unknownHostException.message!!, unknownHostException)
        }
    }
}
