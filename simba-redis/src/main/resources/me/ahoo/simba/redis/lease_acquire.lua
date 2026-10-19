-- Copyright [2021-present] [ahoo wang <ahoowang@qq.com> (https://github.com/Ahoo-Wang)].
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--      http://www.apache.org/licenses/LICENSE-2.0
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

-- Acquires leases for a session (ADR 0010 §7.2).
-- ARGV[1]: '1' when KEYS start with the session keys (session hash, lease index), else '0'; ARGV[2]: session id;
-- ARGV[3]: without the session keys, how long the leases last in milliseconds; ARGV[4..]: with the session keys, the
-- lease keys to record in the lease index (keep-alive extends and reports them, closing the session frees them);
-- ARGV[4]: without the session keys, the Redis time (of the session's node) before which this call must run: the
-- session call's time plus the session lease plus the allowed clock skew.
-- KEYS: [session keys,] lease keys `simba:lease:{name}`.
-- A lease that is free or expired moves to the session with its token advanced by one; a lease the session holds
-- answers its current token. Either way it lasts at least as long as the session does now (on a single node) or one
-- full session lease, never beyond ARGV[4] (on a Cluster, where the session lives on another node). The hash never
-- expires, so the token never resets.
-- Returns {-1} when the session expired, {-2} when a call without the session keys ran at or after ARGV[4] (it
-- changes nothing: the session may have died meanwhile, and keep-alive may have dropped the keys from its index);
-- else {remaining, session lease, Redis time, then per lease key: token, holder, 1 when this call began the term
-- else 0}.
local withSession = ARGV[1] == '1'
local sid = ARGV[2]
local ttl = tonumber(ARGV[3])
local lease = ttl
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local first = 1
if not withSession then
    local deadline = tonumber(ARGV[4])
    if now >= deadline then
        return { -2 }
    end
    ttl = math.min(ttl, deadline - now)
end
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

local reply = { ttl, lease, now }
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
