-- Drops lease keys from a session's lease index (ADR 0010 §7.2) on a Redis Cluster, where an acquisition records its
-- key in one call and takes the lease in another.
-- KEYS[1]: lease index. ARGV: lease key, score pairs as keep-alive read them.
-- The caller passes only keys the session did not hold, read at least one lease after their last attempt began, so
-- no attempt can still take them; a key whose score changed was tried again since and stays.
-- Returns {number of keys dropped}.
local dropped = 0
for i = 1, #ARGV, 2 do
    if redis.call('ZSCORE', KEYS[1], ARGV[i]) == ARGV[i + 1] then
        dropped = dropped + redis.call('ZREM', KEYS[1], ARGV[i])
    end
end
return { dropped }
