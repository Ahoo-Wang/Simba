-- Acquires leases for a session (ADR 0010 §7.2).
-- ARGV[1]: '1' when KEYS start with the session keys (session hash, lease index), else '0'; ARGV[2]: session id;
-- ARGV[3]: without the session keys, the session's remaining milliseconds; ARGV[4..]: with the session keys, the
-- lease keys to record in the lease index (closing the session frees them).
-- KEYS: [session keys,] lease keys `simba:lease:{name}`.
-- A lease that is free or expired moves to the session with its token advanced by one and lasts as long as the
-- session; a lease the session holds answers its current token. The hash never expires, so the token never resets.
-- Returns {-1} when the session expired; else {remaining, then per lease key: token, holder, 1 when this call began
-- the term else 0}.
local withSession = ARGV[1] == '1'
local sid = ARGV[2]
local remaining = tonumber(ARGV[3])

local first = 1
if withSession then
    remaining = redis.call('PTTL', KEYS[1])
    if remaining <= 0 then
        return { -1 }
    end
    if #ARGV >= 4 then
        redis.call('SADD', KEYS[2], unpack(ARGV, 4))
        redis.call('PEXPIRE', KEYS[2], remaining)
    end
    first = 3
end

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local reply = { remaining }
for i = first, #KEYS do
    local state = redis.call('HMGET', KEYS[i], 'session', 'token', 'expires')
    local holder = state[1] or ''
    local token = tonumber(state[2] or '0')
    if holder ~= '' and tonumber(state[3] or '0') > now then
        reply[#reply + 1] = token
        reply[#reply + 1] = holder
        reply[#reply + 1] = 0
    else
        token = token + 1
        redis.call('HSET', KEYS[i], 'session', sid, 'token', token, 'expires', now + remaining)
        reply[#reply + 1] = token
        reply[#reply + 1] = sid
        reply[#reply + 1] = 1
    end
end
return reply
