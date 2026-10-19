-- Ends a session (ADR 0010 §7.2), last step: drops its lease and group indexes once their leases and memberships
-- were freed.
-- KEYS[1]: lease index; KEYS[2]: group index.
-- Returns {number of keys deleted}.
return { redis.call('DEL', KEYS[1], KEYS[2]) }
