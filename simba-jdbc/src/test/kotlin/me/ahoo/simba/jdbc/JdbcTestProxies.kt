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

import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * JDK dynamic proxy helper for faking JDBC interfaces in unit tests (no database needed).
 *
 * Note: no-arg methods receive a null args array from the InvocationHandler bridge,
 * so the handler parameter is nullable.
 */
internal fun <T : Any> jdbcProxy(iface: Class<T>, handler: (Method, Array<Any?>?) -> Any?): T {
    return Proxy.newProxyInstance(
        iface.classLoader,
        arrayOf(iface),
        InvocationHandler { _, method, args -> handler(method, args) }
    ) as T
}
