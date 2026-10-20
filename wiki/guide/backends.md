---
title: Backends
description: What every Simba backend must provide, and how the JDBC (MySQL, PostgreSQL), Redis and Zookeeper backends provide it — storage, where time is measured, where fencing tokens come from, and how to operate each.
---

# Backends

A backend is the one place where nodes agree. Simba asks very little of it, and each requirement comes straight from
[the model](/guide/concepts):

| Requirement | Why |
|---|---|
| A session expires once it was not kept alive for its lease | A crashed node's leases must free themselves |
| An expired session is never revived | Its leases may already belong to someone else |
| A lease belongs to a session only while that session is live | Ownership ends with liveness |
| A lease's token is greater than zero, strictly increases with every new term and never resets | Resources compare tokens to reject stale owners |
| Remaining time is measured by the backend's own clock and returned as a duration | Nodes never compare timestamps with the backend |

Every backend implements the same `LeaseBackend` SPI and passes the same [TCK](/guide/testing#backend-tck), so
elections, locks and groups behave alike on all of them. What differs is *how* each storage meets these requirements,
and therefore what can break them:

| | JDBC | Redis | Zookeeper |
|---|---|---|---|
| Session | Row in `simba_session` | Hash `simba:session:{sid}` with `PX` | A Zookeeper session |
| Lease | Row in `simba_lease`, never deleted | Hash `simba:lease:{name}`, never expires | Ephemeral node `{root}/leases/{name}` |
| Clock | Database `now` | Redis `TIME` | Zookeeper session timeout (servers' elapsed time) |
| Token | Counter in the row, `+1` per term | Counter in the hash, `+1` per term | The node's `czxid` |
| Release broadcast | None | Pub/sub on the lease key | Node watch |
| Token durability | The database's commits | Asynchronous replication, memory | Quorum commits |

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

### How It Works {#jdbc-how-it-works}

- A lease belongs to the session in `session_id` only while that session's `expires_at` is in the future. Acquire is
  one upsert that takes the row when it is free or its session expired, and advances `token` by one. On MySQL the
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

### Operating Notes {#jdbc-operating-notes}

- A release notifies no one: after a clean stop, candidates take over at their next attempt (`renewInterval / 2` plus
  jitter).
- Each statement has a timeout; the starter sets it to the session renew interval (`simba.jdbc.query-timeout`), after
  which the engine gives up on the call anyway. Without Spring, `JdbcLeaseBackend` defaults to 5 s
  (`DEFAULT_QUERY_TIMEOUT`, the default renew interval); pass your renew interval as `queryTimeout` when you change it.
- Give Simba a connection pool that is not starved by the application: a keep-alive that waits for a connection
  longer than the renew interval counts as failed.
- Tokens and session expiries are only as durable as the database's commits. A failover to an asynchronous MySQL
  replica, or the promotion of a PostgreSQL standby that had not received the latest WAL, can lose recent token
  increments and keep-alives, exactly like a [Redis failover](#token-regression-after-a-failover): a token can go back.
  Keep fencing strict across failovers with synchronous replication (MySQL semi-synchronous replication or Group
  Replication, PostgreSQL `synchronous_commit` with synchronous standbys).
- Leases are measured with the database server's wall clock. A step of that clock (an NTP step, a VM migration)
  shortens or lengthens every lease at once, which is not the rate drift that `simba.session.drift` covers; see
  [Failure Modes](/guide/correctness#failure-modes).

## Redis 7

Module `simba-redis` (driver-neutral, Lua scripts behind the `RedisScripting` port) and `simba-spring-redis`
(`SpringRedisScripting`, the Spring Data Redis adapter). `SpringRedisScripting` needs Lettuce, the Spring Boot default
client: with Jedis, subscriptions block the calling thread and `WAIT` is unavailable, so the starter fails startup on a
non-Lettuce `RedisConnectionFactory`.

```kotlin
val backend = RedisLeaseBackend(SpringRedisScripting(redisConnectionFactory))
```

### Keys

| Key | Type | Content |
|---|---|---|
| `simba:lease:{name}` | Hash, never expires | `session`, `token`, `expires` (Redis `TIME`); release clears `session` only, so the token never resets |
| `simba:session:{sid}` | Hash with `PX` = lease | The session; keep-alive, acquire and join fail once it is gone |
| `simba:session:{sid}:leases` | Sorted set, same slot | Lease keys of the session, scored by the acquire attempt's Redis time |
| `simba:session:{sid}:groups` | Set, same slot | Groups the session joined |
| `simba:group:{group}` | Sorted set | Members (session ids) scored by expiry in Redis time |

Release publishes `released` on the lease key's channel (`simba:lease:{name}`); it is the only broadcast and lets
candidates take over at once. Every time decision uses Redis `TIME` inside the scripts.

### How It Works {#redis-how-it-works}

- Leases expire on their own: a new term lasts as long as its session, and a keep-alive extends every lease the session
  holds, found through its lease index, so a lease acquired while a keep-alive was in flight is extended too.
- Keep-alive, acquire and join fail once the session hash's `PTTL` reaches 0. Redis still keeps the key during that
  millisecond, so a bare `PEXPIRE` would revive a session that already expired.
- Closing a session deletes it first, then frees its leases and memberships, then drops its indexes, so a close
  repeated after a failure finishes the job.

### Cluster

On a Redis Cluster (`RedisScripting.clustered`, or `simba.redis.route-by-slot=true`) every script call keeps to one
hash slot: a call first touches the session's slot, then runs one script per slot of the remaining keys. Those later
scripts can reach Redis long after the first one (a starved I/O thread, a driver holding commands until it reconnects),
when the session may have died, and their nodes' clocks may differ from the session node's. Only durations cross
nodes, so a fixed skew between node clocks changes nothing:

- Acquire and join grant one full session lease on the lease node's own clock. A keep-alive that runs between the
  session call and the lease call extends the session but not a lease the session does not hold yet, and the term
  is adopted with that keep-alive's deadline; a full lease covers it. The price is liveness only: a grant to a session
  that died in between outlives it by up to one lease, blocking that name (or keeping a stale member) that long.
- Keep-alive's later scripts carry what the session has left: its new remaining time minus the time the client
  measured since the session call answered. Their node extends nothing beyond its own now plus that, so a lease lasts
  at least as long as its session and outlasts it by at most the delay before the script ran.
- Acquire and join send nothing once the session's remaining time passed since the session call was sent, and fail.
  An acquire answering later than that releases the terms it began and fails, even when other slots granted theirs:
  keep-alive drops a lost lease from the session's index one lease after the attempt, and `owned` would never report
  such a term. Only when that release fails or the reply is lost does a lease stay unreported, until it expires on
  its own.

### Token Regression after a Failover

Redis replication is asynchronous. When a master fails before a replica received the latest `token` increment, the
promoted replica can hand out a token that was already used, or a lower one. A resource that checks tokens can then
accept a write from a node whose term is newer in time but lower in token, or reject a legitimate owner. No client
can prevent this: the deterministic simulation injects such data loss and confirms that tokens then regress
(`premise:tokenReset`), which is why the mitigations are on the backend side:

- Set `simba.redis.wait-replicas` (constructor parameter `waitReplicas`): a new term counts only once that many
  replicas acknowledged it with `WAIT`, otherwise the acquisition fails. This narrows the window; it does not close it,
  and it is not available on a Cluster.
- Enable persistence (AOF) so a restart of a single node does not reset tokens.
- When fencing must hold across failovers, use JDBC on synchronous replication or Zookeeper (quorum commits).
- Leases are measured with Redis `TIME`: a step of the Redis hosts' clocks shortens or lengthens every lease at once.

### Memory Eviction

Lease hashes never expire, and their `token` field is what keeps tokens increasing. If Redis evicts one under memory
pressure (any `allkeys-*` `maxmemory-policy`), the next acquire of that name starts again at token 1: a resource that
checks tokens then rejects the new owner, or a stale owner's lower token looks valid again. Session hashes carry a TTL,
so `volatile-*` policies can evict them, which ends the session early.

- Run the Redis that Simba uses with `maxmemory-policy noeviction` (the Redis default), so a full instance fails
  writes instead of dropping keys; Simba then reports failed calls and terms end at their deadline.
- Or make sure the `simba:*` keys can never be evicted, e.g. a dedicated instance with its own memory budget. A
  separate logical database on the same instance does not help: the policy and the memory limit are per instance.

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

### How It Works {#zookeeper-how-it-works}

- **A Simba session is one Zookeeper session.** The backend opens a dedicated Zookeeper client per session (it does not
  use a shared Curator client) and never reconnects it to another session. The `SessionId` is the Zookeeper session id
  as 16 hex digits.
- **The token is the lease node's `czxid`**: strictly increasing across terms because zxids are global, but not
  consecutive.
- Release deletes the node; candidates watch it and take over at once. Group membership changes are watched too,
  including the creation of a group that did not exist yet.
- A name counts as possibly owned from the moment its create is sent, so a lease whose create succeeded but whose reply
  was lost (connection loss, timeout) is still reported by the next keep-alive, after checking the node's owner, and
  released by the engine. Releases of one session run one at a time.
- The Zookeeper client heartbeats on its own, so a local watchdog closes a session that no keep-alive renewed within
  the lease: a node that stopped renewing never keeps its leases through heartbeats alone.
- While the client is disconnected, or on a follower cut off from the leader, keep-alive and acquire fail, so the local
  deadline runs out; when Zookeeper reports the session expired, the session is `Expired`.

### Why a Keep-Alive Leaves 5/12 of the Timeout

Only the Zookeeper leader expires sessions, while a follower answers reads from its own state until it notices it lost
the leader (`syncLimit` ticks), and reports the session's activity to the leader only on its next ping. A successful
read on a follower therefore proves little about what the leader will do. So a keep-alive first `sync`s through the
leader, and reports a remaining time of `min(leaseDuration, 5/12 × negotiated session timeout)`: the client sends at
least every third of the timeout and the leader pings every half tick (at most a quarter of the timeout), so the leader
saw the session at most `7/12` of the timeout before the reply.

The consequences:

- With the default lease the client renews about every two seconds and publishes `SessionLeaseShortened` once per
  session.
- A node keeps its terms through a pause (GC, network stall) of only about `5/12` of the session timeout: about 6 s
  with the default 15 s lease, against about 15 s before 5.1, and renews about three times as often, each time with a
  round trip to the leader.
- `leaseDuration` keeps its meaning (after a crash the others take over within about a lease), so the session timeout
  is not enlarged. When tolerating pauses matters more than failover time, raise `simba.session.lease-duration` (for
  example 36 s for 5.0's tolerance, with failover after a crash in about 36 s).

### Operating Notes {#zookeeper-operating-notes}

- The session timeout requested is `leaseDuration`; the ensemble clamps it to `[minSessionTimeout,
  maxSessionTimeout]` (by default 2 to 20 ticks). Failover after a crash takes the negotiated timeout.
- The `5/12` bound needs a tick of at most half the session timeout, that is a `minSessionTimeout` of at least the
  default two ticks. Do not configure the servers below it.
- `simba.zookeeper.connection-timeout` bounds how long opening a session may take; it defaults to the renew interval,
  after which the engine gives up on the call. A session that connects later anyway is never kept alive, and the
  watchdog closes it after `leaseDuration`.
- Writes commit through a quorum, so a leader change keeps every token; tokens can only go back if the ensemble's data
  is lost or restored from an older snapshot. Session timeouts are tracked with the servers' elapsed time, so a wall
  clock step on an ensemble host does not shorten them.
- Group names follow the `LeaseName` rules, as on every backend. `members` reads through the client of the session that
  last joined or left the group on this node, so that change is always in the view (Zookeeper orders the requests of a
  session), after a `sync` that brings a follower up to date with the leader. A `sync` is answered locally on the
  leader, so a change made by another node can show there a moment later; membership hints and the periodic refresh
  pick it up.

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
  `ZKClientConfig` bean configures every client (see [Spring Boot](/guide/configuration#properties)).

## Selecting a Backend in Spring Boot

The starter activates the backend whose module is on the classpath; with several, set `simba.backend`. The rules are on
[Spring Boot](/guide/configuration#selecting-the-backend).
