-- Releases leases (ADR 0010 §7.2).
-- KEYS: lease keys `simba:lease:{name}`.
-- ARGV[1]: session id; ARGV[i + 1]: token of the term in KEYS[i] to end, '*' for whatever term the session holds.
-- Frees each lease the session holds in that term, keeping the token, and publishes `released` on the lease key's
-- channel: the only broadcast. Any other release is a no-op.
-- Returns {number of leases freed}.
local sid = ARGV[1]
local freed = 0
for i = 1, #KEYS do
    local state = redis.call('HMGET', KEYS[i], 'session', 'token')
    if state[1] == sid and (ARGV[i + 1] == '*' or tonumber(state[2]) == tonumber(ARGV[i + 1])) then
        redis.call('HSET', KEYS[i], 'session', '', 'expires', 0)
        redis.call('PUBLISH', KEYS[i], 'released')
        freed = freed + 1
    end
end
return { freed }
