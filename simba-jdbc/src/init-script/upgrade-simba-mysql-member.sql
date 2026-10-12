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

-- Adds the member table to an existing schema (Simba >= 4.6, partitioned leadership, ADR 0009).
use simba_db;

create table if not exists simba_member
(
    group_name varchar(66) not null comment 'partition group (ADR 0009)',
    member_id  varchar(128) not null,
    expire_at  bigint unsigned not null comment 'membership expiry, epoch millis in database time',
    primary key (group_name, member_id)
);
