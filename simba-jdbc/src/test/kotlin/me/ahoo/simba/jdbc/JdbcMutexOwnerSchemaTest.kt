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
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class JdbcMutexOwnerSchemaTest {
    @Test
    fun `owner id column can store host contender ids across common address formats`() {
        val initScript = Files.readString(Path.of("src/init-script/init-simba-mysql.sql"))
        val ownerIdColumnLength = Regex("""owner_id\s+\w+\((\d+)\)""")
            .find(initScript)!!
            .groupValues[1]
            .toInt()

        assertThat(ownerIdColumnLength, greaterThanOrEqualTo(128))
    }
}
