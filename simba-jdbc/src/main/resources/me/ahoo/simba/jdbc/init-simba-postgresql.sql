-- Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--      http://www.apache.org/licenses/LICENSE-2.0
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- Simba 5 schema for PostgreSQL 14+ (ADR 0010 §7.1). Times are database time in epoch milliseconds.

create table if not exists simba_session
(
    session_id char(32) collate "C" not null primary key,
    expires_at bigint               not null,
    metadata   varchar(1024)        not null default ''
);

create index if not exists idx_session_expires on simba_session (expires_at);

create table if not exists simba_lease
(
    name       varchar(128) collate "C" not null primary key,
    session_id char(32) collate "C"     not null default '',
    token      bigint                   not null
);

create index if not exists idx_lease_session on simba_lease (session_id);

create table if not exists simba_member
(
    group_name varchar(128) collate "C" not null,
    session_id char(32) collate "C" not null,
    primary key (group_name, session_id)
);

create index if not exists idx_member_session on simba_member (session_id);
