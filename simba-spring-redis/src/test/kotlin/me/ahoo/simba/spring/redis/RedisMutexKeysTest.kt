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

class RedisMutexKeysTest {
    @Test
    fun `names share the mutex hash tag and stay compatible with earlier nodes`() {
        val keys = RedisMutexKeys("naming")

        keys.mutexKey.assert().isEqualTo("simba:{naming}")
        keys.fenceKey.assert().isEqualTo("simba:{naming}:fence")
        keys.tokenKey.assert().isEqualTo("simba:{naming}:token")
        keys.legacyQueueKey.assert().isEqualTo("simba:{naming}:contender")
        keys.contenderChannel("c1").assert().isEqualTo("simba:{naming}:c1")
    }
}
