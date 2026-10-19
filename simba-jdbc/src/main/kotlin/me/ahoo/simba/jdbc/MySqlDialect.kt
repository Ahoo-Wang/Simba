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
 * MySQL 8. `insert … on duplicate key update` cannot carry a `where`, so each assignment repeats the take-over
 * condition. MySQL evaluates `set` left to right with already-updated values: the token assignment must stay first,
 * while `session_id` still holds the previous holder that the condition reads.
 */
object MySqlDialect : Dialect {
    override val name: String = "MySQL"

    /**
     * UTC epoch milliseconds, independent of the session `time_zone`: `unix_timestamp` converts from that zone and
     * jumps by an hour at a daylight saving transition. `utc_timestamp` is evaluated once per statement.
     */
    override val now: String = "(timestampdiff(microsecond, '1970-01-01', utc_timestamp(6)) div 1000)"

    override val initScript: String = "me/ahoo/simba/jdbc/init-simba-mysql.sql"

    private val takeOver: String =
        "simba_lease.session_id <> req.req_session and ${notLiveLocked("simba_lease.session_id")}"

    override val acquire: String =
        """
        insert into simba_lease (name, session_id, token)
        select * from (
            select ? as req_name, s.session_id as req_session, 1 as req_token
              from simba_session s
             where s.session_id = ? and s.expires_at > $now
        ) as req
        on duplicate key update
            token = if($takeOver, simba_lease.token + 1, simba_lease.token),
            session_id = if($takeOver, req.req_session, simba_lease.session_id)
        """.trimIndent()

    override val join: String =
        """
        insert into simba_member (group_name, session_id)
        select * from (
            select ? as req_group, s.session_id as req_session
              from simba_session s
             where s.session_id = ? and s.expires_at > $now
        ) as req
        on duplicate key update group_name = req.req_group
        """.trimIndent()
}
