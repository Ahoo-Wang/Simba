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

package me.ahoo.simba.jdbc

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class MutexOwnerEntityTest {
    @Test
    fun `current time comes from database timestamp`() {
        val entity = MutexOwnerEntity(
            mutex = "db-clock",
            ownerId = "owner",
            acquiredAt = 900,
            ttlAt = 1400,
            transitionAt = 2000
        )
        entity.currentDbAt = 1000

        assertThat(entity.currentAt, equalTo(1000))
    }
}
