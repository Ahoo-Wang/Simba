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

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.hamcrest.Matchers.matchesPattern
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test

class ContenderIdGeneratorTest {
    @Test
    fun `UUID generates 32 hex chars without dashes`() {
        val id = UUIDContenderIdGenerator.generate()

        assertThat(id.length, equalTo(32))
        assertThat(id, matchesPattern("[0-9a-f]{32}"))
    }

    @Test
    fun `UUID generates distinct ids`() {
        assertThat(UUIDContenderIdGenerator.generate(), not(equalTo(UUIDContenderIdGenerator.generate())))
    }

    @Test
    fun `HOST generates counter pid host format`() {
        val id = HostContenderIdGenerator.generate()

        assertThat(id, matchesPattern("\\d+:\\d+@.+"))
    }

    @Test
    fun `HOST increments counter across calls`() {
        val first = HostContenderIdGenerator.generate()
        val second = HostContenderIdGenerator.generate()

        val firstCounter = first.substringBefore(":").toLong()
        val secondCounter = second.substringBefore(":").toLong()
        assertThat(secondCounter, equalTo(firstCounter + 1L))
    }

    @Test
    fun `companion UUID field is the singleton instance`() {
        assertThat(ContenderIdGenerator.UUID, sameInstance(UUIDContenderIdGenerator))
    }

    @Test
    fun `companion HOST field is the singleton instance`() {
        assertThat(ContenderIdGenerator.HOST, sameInstance(HostContenderIdGenerator))
    }
}
