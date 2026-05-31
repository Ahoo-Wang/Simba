# Backend Internals Reference

Use this reference for backend debugging, not for everyday Simba usage. It mirrors the current repository implementation; when backend source changes, update this file in the same change.

## Redis Backend — Lua Scripts

The Redis backend uses three Lua scripts for atomicity. Understanding these helps with debugging and tuning.

### mutex_acquire.lua

Atomically tries to acquire the lock:
1. `SET key contenderId NX PX (ttl + transition)` - set only if not exists, with millisecond expiry.
2. On success: publishes `acquired@@contenderId` on the mutex channel and returns `contenderId@@remainingMs`.
3. On failure (lock exists): adds the contender to a sorted-set wait queue (`ZADD NX score=redisTimeSeconds`) and returns `currentOwnerId@@remainingPttl`.

The sorted set acts as the wait queue. Contenders are notified via Pub/Sub when the lock is released.

### mutex_guard.lua

For the current owner to renew (extend TTL):
1. Checks if the lock is held by this contender (`GET key == contenderId`).
2. If yes: `SET XX PX ttlMs` to extend the expiry. Returns `contenderId@@ttlMs`.
3. If no: returns the current owner info so the contender knows it lost the lock.

### mutex_release.lua

Releases the lock and wakes the next contender:
1. If lock is held by this contender: `DEL key`.
2. Picks the oldest contender from the sorted set (`ZREVRANGE -1 -1`), removes it.
3. Publishes `released@@releasedOwnerId` on the next contender's personal channel to wake it up.

### Pub/Sub Channels

- **Global mutex channel** (`simba:{mutex}`): All contenders subscribe. Receives `acquired@@ownerId` events.
- **Per-contender channel** (`simba:{mutex}:{contenderId}`): Only the specific contender subscribes. Receives `released@@contenderId` events to trigger immediate re-acquisition.

### Message Format

All Pub/Sub messages use `@@` as delimiter:
- `acquired@@{ownerId}` — lock was acquired by ownerId
- `released@@{ownerId}` — lock was released by ownerId

## JDBC Backend — Database Schema

The `simba_mutex` table:

```sql
CREATE TABLE simba_mutex (
    mutex varchar(66) not null primary key comment 'mutex name',
    acquired_at bigint unsigned not null,
    ttl_at bigint unsigned not null,
    transition_at bigint unsigned not null,
    owner_id varchar(128) not null,
    version int unsigned not null
);
```

### Acquire Logic (SQL CAS)

The critical `acquire` SQL atomically updates ownership:

```sql
UPDATE simba_mutex
SET owner_id = #{contenderId},
    acquired_at = currentDbAt,
    ttl_at = currentDbAt + #{ttlMs},
    transition_at = currentDbAt + #{ttlMs} + #{transitionMs},
    version = version + 1
WHERE mutex = #{mutex}
  AND (
    transition_at < #{currentDbAt}          -- lock fully expired
    OR (owner_id = #{contenderId} AND transition_at > #{currentDbAt})  -- current owner renewing
  )
```

This ensures:
- A new contender can only acquire if `transition_at` has passed (hard expiry).
- The current owner can renew if still within the transition window (soft renewal).
- `version` records ownership changes and helps inspect concurrent updates.
- `currentDbAt` comes from MySQL `current_timestamp(3)` to avoid application-node clock skew.

## Zookeeper Backend — Curator Integration

The Zookeeper backend is the simplest — it delegates entirely to Apache Curator's `LeaderLatch`:

- Path: `/simba/{mutex}`
- Contender ID: set as `LeaderLatch.setId(contenderId)`
- Leadership changes: `LeaderLatchListener.isLeader()` / `notLeader()` callbacks
- Close mode: `CloseMode.NOTIFY_LEADER` (notifies on close so the next contender can take over)

No TTL/transition tuning needed — Zookeeper handles session management and ephemeral nodes.
