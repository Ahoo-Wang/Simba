-- Extends a session and the leases it holds (ADR 0010 §7.2).
-- ARGV[1]: '1' when KEYS start with the session keys (session hash, lease index, group index), else '0';
-- ARGV[2]: session id; ARGV[3]: lease in milliseconds; ARGV[4]: number of lease keys after the session keys.
-- KEYS: [session keys,] lease keys `simba:lease:{name}`, then group keys `simba:group:{group}`.
-- A lease is extended only while the session holds it and it has not expired; a membership only while it has not
-- expired. Nothing expired is ever revived.
-- Returns {-1} when the session expired; else {lease, token per lease key (0 when not held), then, with the session
-- keys, every group key the session joined}.
local withSession = ARGV[1] == '1'
local sid = ARGV[2]
local lease = tonumber(ARGV[3])
local leases = tonumber(ARGV[4])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local first = 1
local groups = {}
if withSession then
    if redis.call('PEXPIRE', KEYS[1], lease) == 0 then
        return { -1 }
    end
    redis.call('PEXPIRE', KEYS[2], lease)
    redis.call('PEXPIRE', KEYS[3], lease)
    groups = redis.call('SMEMBERS', KEYS[3])
    first = 4
end

local reply = { lease }
for i = first, first + leases - 1 do
    local state = redis.call('HMGET', KEYS[i], 'session', 'token', 'expires')
    if state[1] == sid and tonumber(state[3] or '0') > now then
        redis.call('HSET', KEYS[i], 'expires', now + lease)
        reply[#reply + 1] = tonumber(state[2])
    else
        reply[#reply + 1] = 0
    end
end
for i = first + leases, #KEYS do
    local score = redis.call('ZSCORE', KEYS[i], sid)
    if score and tonumber(score) > now then
        redis.call('ZADD', KEYS[i], now + lease, sid)
    end
end
for _, group in ipairs(groups) do
    reply[#reply + 1] = group
end
return reply
