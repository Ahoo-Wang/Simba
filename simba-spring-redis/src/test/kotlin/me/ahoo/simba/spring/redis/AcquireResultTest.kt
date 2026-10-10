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
package me.ahoo.simba.spring.redis

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AcquireResultTest {
    @Test
    fun `parses owner and remaining lease`() {
        val before = System.currentTimeMillis()

        val result = AcquireResult.of(listOf("c1", 16000L))

        result.ownerId.assert().isEqualTo("c1")
        result.transitionAt.assert().isBetween(before + 16000, System.currentTimeMillis() + 16000)
    }

    @Test
    fun `empty owner means no owner`() {
        AcquireResult.of(listOf("", 0L)).assert().isEqualTo(AcquireResult.NONE)
    }

    @Test
    fun `rejects malformed replies`() {
        assertThrows<IllegalStateException> { AcquireResult.of(listOf("c1")) }
    }
}
