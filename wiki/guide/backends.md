---
title: Backends
description: How the JDBC, Redis and Zookeeper backends store a lease, what each needs to run, and how each fails.
---

# Backends

All backends implement the same contract (see [Correctness](/guide/correctness)), verified by the shared
[TCK](/contributing/#backend-tck). They differ in where the lease lives, which clock decides it, and what happens when
the backend itself fails.

## JDBC (MySQL)

**Storage.** One row per mutex in `simba_mutex`. Create the table once with
[`init-simba-mysql.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/init-script/init-simba-mysql.sql);
rows are inserted on first use.

```sql
create table if not exists simba_mutex
(
    mutex         varchar(66)     not null primary key comment 'mutex name',
    acquired_at   bigint unsigned not null,
    ttl_at        bigint unsigned not null,
    transition_at bigint unsigned not null,
    owner_id      varchar(128)    not null,
    version       int unsigned    not null,
    fencing_token bigint unsigned not null default 0
);
```

**Acquire and renew** are one conditional `UPDATE`, evaluated with the database clock: it succeeds when the lease has
ended (`transition_at < now`) or when the caller already owns it and the lease has not ended. The row is then read
back in the same transaction together with the database time (`current_at`), so lease decisions never compare wall
clocks of different machines.

**Fencing tokens** come from `fencing_token`, advanced in that `UPDATE` only when a new term starts. They are on by
default (`simba.jdbc.fencing=true`). Tables created before 3.3 need
[`upgrade-simba-mysql-fencing-token.sql`](https://github.com/Ahoo-Wang/Simba/blob/main/simba-jdbc/src/init-script/upgrade-simba-mysql-fencing-token.sql),
or set `simba.jdbc.fencing=false`.

**Operating notes**

- The SQL is MySQL-specific (`unix_timestamp(current_timestamp(3))`, `if(...)`, left-to-right `SET` evaluation).
- Contenders poll: after a clean `stop()` the next owner takes over at its next scheduled attempt, not immediately.
- The starter sets the statement timeout to `simba.jdbc.ttl`, so a hung query cannot block contention for longer
  than one lease.
- Database unavailable: owners keep ownership while their lease is valid, then revoke it locally. Nobody owns the
  mutex until the database is back.

## Redis

**Storage.** Three keys per mutex, sharing the `{mutex}` hash tag so they live in one cluster slot:

| Key | Content | Expiry |
|---|---|---|
| `simba:{mutex}` | Owner's contender id; also the pub/sub channel | `ttl + transition` |
| `simba:{mutex}:fence` | Fencing counter | Never |
| `simba:{mutex}:token` | Fencing token of the current term | With the lease |

**Acquire** is `SET NX PX` in a Lua script that, on success, increments the counter and publishes
`acquired@@{contenderId}`. **Renew** is `SET XX PX` by the owner only. **Release** deletes the lease and publishes
`released@@{contenderId}`, which makes every subscribed contender contend immediately. Each node derives `transitionAt`
from the key's `PTTL` and its local clock.

**Operating notes**

- **Persistence matters for fencing.** The counter only increases across Redis restarts if it is persisted (AOF with
  `appendfsync always` or equivalent). Without persistence a restart resets it and tokens can go backwards.
- Replication is asynchronous: a failover to a replica that missed the latest `SET` can grant a second lease. Use
  fencing tokens if that matters, or use a backend with synchronous replication.
- Mixed versions: 4.x nodes require every node of the same mutex to run Simba 3.2 or later.

## Zookeeper

**Storage.** A Curator `LeaderLatch` at `/simba/{mutex}`: each contender creates an ephemeral sequential node, and the
lowest one leads. There is no TTL: `ttl` and `transition` do not apply, and leadership ends when the leader closes its
latch or its session expires. Provide a started `CuratorFramework` bean; the starter does not create one.

**Fencing tokens** are the `czxid` of the winning latch node. ZooKeeper transaction ids grow monotonically across the
ensemble, so each new leader gets a larger token. (The node's sequence number would not work: the parent is a
container node that ZooKeeper deletes when empty, which restarts the sequence.)

**Operating notes**

- Failover after a crash takes the session timeout configured on your `CuratorFramework`.
- On connection loss (`SUSPENDED`) Curator revokes leadership; the node contends again after reconnecting and gets a
  new token.
- Simba observes only its own leadership on Zookeeper: non-leaders do not know who leads, and the
  [`simba` endpoint](/guide/observability#actuator-endpoint) shows no current owner on them. Contention metrics are
  not reported because Curator performs the contention.

## Selecting a Backend in Spring Boot

Each backend activates when its module is on the classpath, `simba.enabled` and `simba.<backend>.enabled` are `true`
(the default), and its infrastructure bean exists. If more than one backend module is active, set
`simba.backend=jdbc|redis|zookeeper`; startup fails otherwise, instead of depending on auto-configuration order.
