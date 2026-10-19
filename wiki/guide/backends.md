---
title: Backends
description: How the JDBC (MySQL, PostgreSQL), Redis and Zookeeper backends store sessions, leases and group members, where fencing tokens come from, and how to operate each.
---

# Backends

Every backend implements the same `LeaseBackend` SPI and passes the same
[TCK](/guide/testing#backend-tck), so elections, locks and groups behave the same on all of them. They differ in
storage, in where time is measured, and in how durable the fencing token is.

Every backend follows the same rules: a session expires once it was not kept alive for its lease; an expired session
is never revived; a lease belongs to a session only while that session is live; a lease's token is greater than zero,
strictly increases with every new term and never resets; and every remaining time is measured by the backend's clock
and returned as a duration.

## JDBC (MySQL 8, PostgreSQL 14+)

Module `simba-jdbc`, class `JdbcLeaseBackend` with a `Dialect` (`Dialect.MYSQL`, `Dialect.POSTGRESQL`):

```kotlin
val backend = JdbcLeaseBackend.mysql(dataSource)      // or JdbcLeaseBackend.postgres(dataSource)
```

### Schema

Create the three tables once with the script of your database:
[`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-mysql.sql)
or
[`init-simba-postgresql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/main/resources/me/ahoo/simba/jdbc/init-simba-postgresql.sql)
(both on the classpath under `me/ahoo/simba/jdbc/`). The MySQL version:

```sql
create table if not exists simba_session
(
    session_id char(32) character set ascii collate ascii_bin not null primary key,
    expires_at bigint                                        not null comment 'database time, epoch ms',
    metadata   varchar(1024)                                 not null default '',
    index idx_session_expires (expires_at)
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
    group_name varchar(128) character set utf8mb4 collate utf8mb4_bin not null,
    session_id char(32) character set ascii collate ascii_bin not null,
    primary key (group_name, session_id),
    index idx_member_session (session_id)
);
```

| Table | One row per | Notes |
|---|---|---|
| `simba_session` | Session | `expires_at` in database time; one `UPDATE` per keep-alive renews the session and, through it, every lease and membership of the node |
| `simba_lease` | Lease name ever acquired | Never deleted, so the token never resets; release sets `session_id` to `''` and keeps the token |
| `simba_member` | Group member | Live only while its session is |

Lease and group names are case-sensitive (binary collations on MySQL, `"C"` on PostgreSQL).

### How It Works

- A lease belongs to the session in `session_id` only while that session's `expires_at` is in the future. Acquire is
  one upsert that takes the row when it is free or its session expired and advances `token` by one. On MySQL the
  `token` assignment comes first in `SET`, because MySQL evaluates `SET` left to right with already-updated values.
- The take-over reads the holder's session row with a shared lock (`for share`), so it serializes with that session's
  keep-alive at any isolation level: a renewal that commits concurrently either wins, or sees the lease gone in the
  same keep-alive reply.
- Every time comparison uses the database's own clock (`now`: UTC epoch milliseconds, constant within a statement and
  independent of the connection's time zone), so application hosts' clocks never matter.
- Expired sessions are cleaned up lazily: a lease of an expired session is free for the next acquirer, and a
  keep-alive that finds its session expired removes it. The rest (sessions of crashed processes) is removed by
  `purgeExpired()`, which runs at most once per `purgeInterval` (default one minute) after an `openSession` or
  `keepAlive`, up to 1000 sessions per run, along `idx_session_expires`.

### Operating Notes

- A release does not notify anyone: after a clean stop, candidates take over at their next attempt
  (`renewInterval / 2` plus jitter).
- Each statement has a timeout; the starter sets it to the session renew interval (`simba.jdbc.query-timeout`), after
  which the engine gives up on the call anyway.
- Give Simba a connection pool that is not starved by the application: a keep-alive that waits for a connection
  longer than the renew interval counts as failed.

## Redis 7

Module `simba-redis` (driver-neutral, Lua scripts behind the `RedisScripting` port) and `simba-spring-redis`
(`SpringRedisScripting`, the Spring Data Redis adapter). `SpringRedisScripting` needs Lettuce, the Spring Boot default
client: with Jedis, subscriptions block the calling thread and `WAIT` is unavailable, so the starter fails startup on a
non-Lettuce `RedisConnectionFactory`:

```kotlin
val backend = RedisLeaseBackend(SpringRedisScripting(redisConnectionFactory))
```

### Keys

| Key | Type | Content |
|---|---|---|
| `simba:lease:{name}` | Hash, never expires | `session`, `token`, `expires` (Redis `TIME`); release clears `session` only, so the token never resets |
| `simba:session:{sid}` | Hash with `PX` = lease | The session; keep-alive, acquire and join fail once it is gone |
| `simba:session:{sid}:leases` | Set, same slot | Leases of the session |
| `simba:session:{sid}:groups` | Set, same slot | Groups the session joined |
| `simba:group:{group}` | Sorted set | Members (session ids) scored by expiry in Redis time |

Release publishes `released` on the lease key's channel (`simba:lease:{name}`); it is the only broadcast and lets
candidates take over at once. Every time decision uses Redis `TIME` inside the scripts.

A new term lasts as long as its session, and a keep-alive extends only the leases the node still holds, so leases
expire on their own even if a session key outlives them.

### Cluster

On a Redis Cluster (`RedisScripting.clustered`, or `simba.redis.route-by-slot=true`) every script call keeps to one
hash slot: a call first touches the session's slot, then runs one script per slot of the remaining keys. The lease
and session slots may then live on nodes whose clocks differ; a skew between them shortens or lengthens leases by that
much.

### Token Regression after a Failover

Redis replication is asynchronous. When a master fails before a replica received the latest `token` increment, the
promoted replica can hand out a token that was already used, or a lower one. A resource that checks tokens can then
accept a write from a node whose term is newer in time but lower in token, or reject a legitimate owner.

- Set `simba.redis.wait-replicas` (constructor parameter `waitReplicas`): a new term counts only once that many
  replicas acknowledged it with `WAIT`, otherwise the acquisition fails. This narrows the window; it does not close it,
  and it is not available on a Cluster.
- Enable persistence (AOF) so a restart of a single node does not reset tokens.
- When fencing must be strict, use JDBC or Zookeeper.

## Zookeeper

Module `simba-zookeeper`, class `ZookeeperLeaseBackend`:

```kotlin
val backend = ZookeeperLeaseBackend("zk1:2181,zk2:2181,zk3:2181", root = "/simba")
```

### Nodes

| Path | Kind | Content |
|---|---|---|
| `{root}/leases/{name}` | Ephemeral | The session id; the owner is the node's `ephemeralOwner`, the token its `czxid` |
| `{root}/groups/{group}/{sessionId}` | Ephemeral, under a container node | Group member |

`root` defaults to `/simba`.

### How It Works

- A Simba session is one Zookeeper session: the backend opens a dedicated Zookeeper client per session (it does not
  use a shared Curator client) and never reconnects it to another session. The `SessionId` is the Zookeeper session id
  as 16 hex digits.
- The token is the lease node's `czxid`: strictly increasing across terms because zxids are global, but not
  consecutive.
- Release deletes the node; candidates watch it and take over at once. Group membership changes are watched too,
  including the creation of a group that did not exist yet.
- A name counts as possibly owned from the moment its create is sent, so a lease whose create succeeded but whose reply
  was lost (connection loss, timeout) is still reported by the next keep-alive, after checking the node's owner, and
  released by the engine. Releases of one session run one at a time.
- The Zookeeper client heartbeats on its own, so a local watchdog closes a session that no keep-alive renewed within
  the lease: a node that stopped renewing never keeps its leases through heartbeats alone.
- Remaining time is `min(leaseDuration, negotiated session timeout)`. While the client is disconnected, keep-alive and
  acquire fail, so the local deadline runs out; when Zookeeper reports the session expired, the session is `Expired`.

### Operating Notes

- The session timeout requested is `leaseDuration`; the ensemble clamps it to `[minSessionTimeout,
  maxSessionTimeout]` (by default 2 to 20 ticks). Failover after a crash takes the negotiated timeout.
- `simba.zookeeper.connection-timeout` bounds how long opening a session may take.
- Group names follow the `LeaseName` rules (1–128 characters of `[A-Za-z0-9._:-]`, not `.` or `..`), as on every
  backend. `members` syncs the group path first, so a join that just returned is always in the view.

### Security

By default the nodes get `OPEN_ACL_UNSAFE`: any client of the ensemble can delete or pre-create lease nodes and forge
group members. On a shared ensemble, authenticate and restrict the ACL:

```kotlin
val backend = ZookeeperLeaseBackend(
    "zk1:2181,zk2:2181,zk3:2181",
    authInfo = listOf(AuthInfo("digest", "simba:secret".toByteArray())),
    aclProvider = myAclProvider, // e.g. ZooDefs.Ids.CREATOR_ALL_ACL for every path
    clientConfig = ZKClientConfig(), // TLS or SASL settings, optional
)
```

- Every client (each session's and the one `members` reads with) adds `authInfo` and is built with `clientConfig`.
- Every node the backend creates gets the ACL of `aclProvider`, including the parents it creates. Parents that already
  exist keep their ACL, and the ACL of `{root}/leases` and `{root}/groups/{group}` decides who may create or delete
  leases and members: create them with the backend's identity, or set their ACL yourself.
- Spring Boot: `simba.zookeeper.auth.scheme` / `simba.zookeeper.auth.credentials` and `simba.zookeeper.acl`
  (`auto` gives creator-only nodes once credentials are set); an `ACLProvider` bean replaces the ACL and a
  `ZKClientConfig` bean configures every client (see [Configuration](./configuration.md)).

## Selecting a Backend in Spring Boot

Each backend auto-configuration activates when its module is on the classpath, `simba.enabled` and
`simba.<backend>.enabled` are not `false`, and `simba.backend` is unset or names it. It defines the `LeaseBackend`
bean unless the application defines one.

With several backend modules on the classpath, set `simba.backend=jdbc|redis|zookeeper`; startup fails otherwise, and
also when `simba.backend` names a backend that is not active. To use a backend the starter does not configure (or a
`LeaseBackend` of your own), define the `LeaseBackend` bean yourself. See [Spring Boot](/guide/configuration).
