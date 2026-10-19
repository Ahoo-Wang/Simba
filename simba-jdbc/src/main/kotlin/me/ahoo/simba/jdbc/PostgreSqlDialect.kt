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

/**
 * PostgreSQL 14+. `insert … on conflict do update … where` takes over a lease in one statement; PostgreSQL evaluates
 * every `set` expression against the previous row, so assignment order does not matter.
 */
object PostgreSqlDialect : Dialect {
    override val name: String = "PostgreSQL"

    override val now: String = "cast(extract(epoch from statement_timestamp()) * 1000 as bigint)"

    override val initScript: String = "me/ahoo/simba/jdbc/init-simba-postgresql.sql"

    override val acquire: String =
        """
        insert into simba_lease (name, session_id, token)
        select cast(? as varchar(128)), s.session_id, 1
          from simba_session s
         where s.session_id = ? and s.expires_at > $now
        on conflict (name) do update
           set token = simba_lease.token + 1, session_id = excluded.session_id
         where simba_lease.session_id <> excluded.session_id
           and ${notLiveLocked("simba_lease.session_id")}
        """.trimIndent()

    override val join: String =
        """
        insert into simba_member (group_name, session_id)
        select cast(? as varchar(128)), s.session_id
          from simba_session s
         where s.session_id = ? and s.expires_at > $now
        on conflict do nothing
        """.trimIndent()
}
