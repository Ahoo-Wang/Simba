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

import me.ahoo.simba.SimbaException
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.sameInstance
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import javax.sql.DataSource

/**
 * Unit tests for the exception-wrapping contract of acquireAndGetOwner.
 * No database required: JDBC interfaces are faked with JDK proxies, and the
 * thrown failures deliberately carry a null message (e.g. JVM NullPointerException,
 * StackOverflowError) — message!! would replace the intended SimbaException with a
 * KotlinNullPointerException and lose the cause chain.
 */
class JdbcMutexOwnerRepositoryExceptionTest {
    @Test
    fun `acquireAndGetOwner wraps null-message statement failure as SimbaException keeping the cause`() {
        // null message by construction; a RuntimeException passes the JDK proxy's
        // declared-exception check (undeclared checked exceptions get wrapped in
        // UndeclaredThrowableException before reaching the repository)
        val boom = IllegalStateException()
        val repository = JdbcMutexOwnerRepository(dataSourceFailingStatementsWith(boom))

        val error = assertThrows<SimbaException> {
            repository.acquireAndGetOwner("m", "c1", 10_000, 6_000)
        }

        assertThat(error.cause, sameInstance(boom))
    }

    @Test
    fun `acquireAndGetOwner wraps null-message connection failure as SimbaException keeping the cause`() {
        val boom = SQLException() // message is null by construction
        val repository = JdbcMutexOwnerRepository(
            proxy(DataSource::class.java) { method, _ ->
                if (method.name == "getConnection") throw boom
                null
            }
        )

        val error = assertThrows<SimbaException> {
            repository.acquireAndGetOwner("m", "c1", 10_000, 6_000)
        }

        assertThat(error.cause, sameInstance(boom))
    }

    private fun dataSourceFailingStatementsWith(boom: Throwable): DataSource {
        val statement = proxy(PreparedStatement::class.java) { method, _ ->
            if (method.name == "executeUpdate") throw boom
            null
        }
        val connection = proxy(Connection::class.java) { method, _ ->
            when (method.name) {
                "prepareStatement" -> statement
                "getAutoCommit" -> true
                else -> null
            }
        }
        return proxy(DataSource::class.java) { method, _ ->
            if (method.name == "getConnection") connection else null
        }
    }

    private fun <T : Any> proxy(iface: Class<T>, handler: (Method, Array<Any?>?) -> Any?): T {
        return Proxy.newProxyInstance(
            iface.classLoader,
            arrayOf(iface),
            InvocationHandler { _, method, args -> handler(method, args) }
        ) as T
    }
}
