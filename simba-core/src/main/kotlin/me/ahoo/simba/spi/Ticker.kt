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

package me.ahoo.simba.spi

/**
 * Monotonic time source in nanoseconds. Only differences between two readings are meaningful.
 *
 * The engine and every `Term` read time only through a ticker, so tests can substitute a virtual one.
 */
fun interface Ticker {
    fun nanoTime(): Long

    companion object {
        /** [System.nanoTime]. */
        @JvmField
        val SYSTEM: Ticker = Ticker { System.nanoTime() }
    }
}
