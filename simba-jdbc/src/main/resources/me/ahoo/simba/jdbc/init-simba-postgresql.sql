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
