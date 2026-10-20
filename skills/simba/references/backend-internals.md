# Backend Internals Reference

Use this reference for backend debugging, not for everyday Simba usage. It mirrors the Simba 5 implementation
(ADR 0010); when backend source changes, update this file in the same change. User-facing detail:
https://simba.ahoo.me/guide/backends.

## Common Contract (`LeaseBackend`)

- One backend round trip per call (or one per Redis slot); no scheduling, no notification, no local lease state.
- Remaining times are measured by the backend's clock and returned as durations; the client turns them into a local
  deadline `sentAt + remaining × (1 − drift) − margin`.
- Tokens are > 0, strictly increase per name, never reset. Release takes `(name → token)` and is a no-op for any other
  token.
- An expired session is never revived: `keepAlive` → `Expired`, `acquire` → `SessionExpiredException`.
- Hints (`Released`, `MembersChanged`) only accelerate.

## JDBC (`JdbcLeaseBackend`, `Dialect.MYSQL` / `Dialect.POSTGRESQL`)

Tables (`me/ahoo/simba/jdbc/init-simba-{mysql,postgresql}.sql`):

- `simba_session(session_id char(32) pk, expires_at bigint, metadata varchar(1024))` — `expires_at` in database
  epoch ms.
- `simba_lease(name varchar(128) pk, session_id char(32) default '', token bigint)` — rows never deleted; `''` = free.
- `simba_member(group_name, session_id)` pk on both.

Behavior:

- Keep-alive: `update simba_session set expires_at = now + lease where session_id = ? and expires_at > now`; 0 rows →
  `Expired` (and the session row is removed).
- Acquire: upsert that takes the row when free or owned by an expired session, `token = token + 1`; the holder's
  session row is read `for share`, so a take-over serializes with that session's keep-alive. On MySQL the `token`
  assignment is first in `SET` (left-to-right evaluation). Already owned → current token.
- Release: `update simba_lease set session_id = '' where name = ? and session_id = ? and token = ?`.
- Members: `simba_member` joined with live sessions. `purgeExpired()` cleans leftover expired sessions, automatically at most once per `purgeInterval` (default 1 min) after `openSession` / `keepAlive`.
- No hints: candidates take over released leases at their next attempt.
- Per-statement `queryTimeout`: default 5 s (`DEFAULT_QUERY_TIMEOUT`, the default renew interval); the starter passes
  the configured renew interval. Zero waits indefinitely.

## Redis (`RedisLeaseBackend` over `RedisScripting`; Lua in `simba-redis/src/main/resources/me/ahoo/simba/redis/`)

Keys:

- `simba:lease:{name}` hash `{session, token, expires}` — never expires, never deleted; `expires` in Redis `TIME`.
- `simba:session:{sid}` hash with `PX = lease`, plus `simba:session:{sid}:leases` and `:groups` sets (same slot).
- `simba:group:{group}` sorted set of session ids scored by expiry.

Scripts: `lease_session_open`, `lease_keep_alive`, `lease_acquire`, `lease_release`, `lease_session_close`,
`lease_group_join`, `lease_group_leave`, `lease_group_members`.

- Acquire takes a lease whose `session` is empty or whose `expires` passed, `token + 1`, `expires = now + session
  remaining`; fails once the session key is gone.
- Keep-alive extends the session and only the `held` leases still owned (leases expire on their own).
- Release clears `session` and publishes `released` on the lease key's channel — the only broadcast.
- `routeBySlot` (Cluster): session slot first, then one script per slot. Only durations cross nodes: acquire and join
  grant one full session lease on the slot node's clock (covers a keep-alive between the two calls); keep-alive's
  slot scripts carry what the session has left and extend nothing beyond their node's now plus that. Acquire and
  join send nothing once the session's
  remaining time passed; an acquire answering later releases what it began and fails.
- `waitReplicas`: `WAIT n timeout` after a new term; not on Cluster. Failover can still lose a token increment.

## Zookeeper (`ZookeeperLeaseBackend`)

- One raw `ZooKeeper` client per session (requested timeout = lease), never reconnected to another session;
  `SessionId` = zk session id as 16 hex digits.
- Lease: ephemeral `{root}/leases/{name}` with the session id as data; owner = `ephemeralOwner`; token = `czxid`
  (strictly increasing, not consecutive). Exists watch → `Released` hint.
- Members: ephemeral `{root}/groups/{group}/{sessionId}` under a container node; children watch → `MembersChanged`.
- A local watchdog closes a session that no keep-alive renewed within the lease (the client heartbeats on its own).
- Remaining = `min(lease, negotiated timeout)`; disconnected → keep-alive / acquire fail; zk-expired → `Expired`.
- `root` defaults to `/simba`.
