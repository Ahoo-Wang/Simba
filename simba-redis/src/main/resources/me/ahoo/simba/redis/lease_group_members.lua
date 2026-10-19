-- Live members of a group (ADR 0010 §7.2).
-- KEYS[1]: group sorted set `simba:group:{group}`, scored by expiry in Redis time.
-- Drops the expired members, then returns the rest.
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
return redis.call('ZRANGE', KEYS[1], 0, -1)
