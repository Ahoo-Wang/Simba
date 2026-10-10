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

import me.ahoo.test.asserts.assert
import org.junit.jupiter.api.Test

class FencingTokenTest {
    @Test
    fun `owners default to no fencing token`() {
        MutexOwner("c1").fencingToken.assert().isEqualTo(MutexOwner.NO_FENCING_TOKEN)
        MutexOwner.NONE.fencingToken.assert().isEqualTo(MutexOwner.NO_FENCING_TOKEN)
    }

    @Test
    fun `contend service exposes the token only while owner`() {
        val contender = FakeMutexContender("m", "c1")
        val service = FakeMutexContendService(contender)
        service.start()

        service.publishOwner(MutexOwner("c1", fencingToken = 42)).join()
        service.fencingToken.assert().isEqualTo(42)

        service.publishOwner(MutexOwner("c2", fencingToken = 43)).join()
        service.fencingToken.assert().isEqualTo(MutexOwner.NO_FENCING_TOKEN)
        service.stop()
    }
}
