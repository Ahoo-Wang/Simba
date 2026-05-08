# Backend Internals Reference

## Redis Backend — Lua Scripts

The Redis backend uses three Lua scripts for atomicity. Understanding these helps with debugging and tuning.

### mutex_acquire.lua

Atomically tries to acquire the lock:
1. `SET key contenderId NX PX transition` — set only if not exists, with millisecond expiry.
2. On success: publishes `acquired@@contenderId` on the mutex channel, returns `contenderId@@transitionMs`.
3. On failure (lock exists): adds contender to a sorted set wait queue (`ZADD NX score=timestamp`), returns current owner info.

The sorted set acts as a FIFO queue — contenders are notified via Pub/Sub when the lock is released.

### mutex_guard.lua

For the current owner to renew (extend TTL):
1. Checks if the lock is held by this contender (`GET key == contenderId`).
2. If yes: `SET XX PX transition` to extend the expiry. Returns success.
3. If no: returns the current owner info so the contender knows it lost the lock.

### mutex_release.lua

Releases the lock and wakes the next contender:
1. If lock is held by this contender: `DEL key`.
2. Picks the oldest contender from the sorted set (`ZREVRANGE -1 -1`), removes it.
3. Publishes `released@@nextContenderId` on the contender's personal channel to wake them up.

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
    mutex VARCHAR(127) NOT NULL PRIMARY KEY COMMENT 'mutex name',
    owner_id VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'current owner contender ID',
    ttl_at BIGINT NOT NULL DEFAULT 0 COMMENT 'TTL expiry timestamp (ms)',
    transition_at BIGINT NOT NULL DEFAULT 0 COMMENT 'transition/grace period expiry (ms)',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'optimistic lock version',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) COMMENT='Simba distributed mutex table';
```

### Acquire Logic (SQL CAS)

The critical `acquire` SQL atomically updates ownership:

```sql
UPDATE simba_mutex
SET owner_id = #{contenderId},
    ttl_at = #{ttlAt},
    transition_at = #{transitionAt},
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
- `version` column prevents lost updates under concurrent access.
- `currentDbAt` comes from `SELECT NOW()` on the DB server to avoid application-node clock skew.

## Zookeeper Backend — Curator Integration

The Zookeeper backend is the simplest — it delegates entirely to Apache Curator's `LeaderLatch`:

- Path: `/simba/{mutex}`
- Contender ID: set as `LeaderLatch.setId(contenderId)`
- Leadership changes: `LeaderLatchListener.isLeader()` / `notLeader()` callbacks
- Close mode: `CloseMode.NOTIFY_LEADER` (notifies on close so the next contender can take over)

No TTL/transition tuning needed — Zookeeper handles session management and ephemeral nodes.
