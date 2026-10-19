-- Acquires leases for a session (ADR 0010 §7.2).
-- ARGV[1]: '1' when KEYS start with the session keys (session hash, lease index), else '0'; ARGV[2]: session id;
-- ARGV[3]: without the session keys, how long the leases last in milliseconds; ARGV[4..]: with the session keys, the
-- lease keys to record in the lease index (keep-alive extends and reports them, closing the session frees them).
-- KEYS: [session keys,] lease keys `simba:lease:{name}`.
-- A lease that is free or expired moves to the session with its token advanced by one; a lease the session holds
-- answers its current token. Either way it lasts at least as long as the session does now (on a single node) or one
-- full session lease (ARGV[3] on a Cluster, where the session lives on another node). The hash never expires, so
-- the token never resets.
-- Returns {-1} when the session expired; else {remaining, session lease, then per lease key: token, holder, 1 when
-- this call began the term else 0}.
local withSession = ARGV[1] == '1'
local sid = ARGV[2]
local ttl = tonumber(ARGV[3])
local lease = ttl
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local first = 1
if withSession then
    ttl = redis.call('PTTL', KEYS[1])
    if ttl <= 0 then
        return { -1 }
    end
    lease = tonumber(redis.call('HGET', KEYS[1], 'lease') or ttl)
    for i = 4, #ARGV do
        redis.call('ZADD', KEYS[2], now, ARGV[i])
    end
    if #ARGV >= 4 then
        redis.call('PEXPIRE', KEYS[2], ttl)
    end
    first = 3
end

local reply = { ttl, lease }
for i = first, #KEYS do
    local state = redis.call('HMGET', KEYS[i], 'session', 'token', 'expires')
    local holder = state[1] or ''
    local token = tonumber(state[2] or '0')
    local expires = tonumber(state[3] or '0')
    if holder ~= '' and expires > now then
        if holder == sid and now + ttl > expires then
            redis.call('HSET', KEYS[i], 'expires', now + ttl)
        end
        reply[#reply + 1] = token
        reply[#reply + 1] = holder
        reply[#reply + 1] = 0
    else
        token = token + 1
        redis.call('HSET', KEYS[i], 'session', sid, 'token', token, 'expires', now + ttl)
        reply[#reply + 1] = token
        reply[#reply + 1] = sid
        reply[#reply + 1] = 1
    end
end
return reply
