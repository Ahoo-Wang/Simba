-- Simba 5 schema for MySQL 8 (ADR 0010 §7.1). Times are database time in epoch milliseconds.
-- Ids and names are ASCII with binary collation: lease names are case-sensitive.

create table if not exists simba_session
(
    session_id char(32) character set ascii collate ascii_bin not null primary key,
    expires_at bigint                                        not null comment 'database time, epoch ms',
    metadata   varchar(1024)                                 not null default ''
);

create table if not exists simba_lease
(
    name       varchar(128) character set ascii collate ascii_bin not null primary key,
    session_id char(32) character set ascii collate ascii_bin     not null default '' comment 'empty = free',
    token      bigint                                             not null comment 'term number, never reset',
    index idx_lease_session (session_id)
);

create table if not exists simba_member
(
    group_name varchar(128)                                  not null,
    session_id char(32) character set ascii collate ascii_bin not null,
    primary key (group_name, session_id),
    index idx_member_session (session_id)
);
