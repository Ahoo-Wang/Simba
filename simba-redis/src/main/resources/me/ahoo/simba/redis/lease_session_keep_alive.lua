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

-- Extends a session (ADR 0010 §7.2), and on a single node the leases the caller listed.
-- KEYS: session hash, lease index (sorted set of the lease keys the session tried, scored by the attempt's Redis
-- time), group index, then optional lease keys `simba:lease:{name}` to extend in this call (a single node only: it
-- also drops those the session no longer holds from the lease index, which is safe because an acquisition there
-- records its key and takes the lease in one script).
-- ARGV[1]: session id; ARGV[2]: lease in milliseconds.
-- A lease is extended only while the session holds it and it has not expired; nothing expired is ever revived.
-- Returns {-1} when the session expired; else {lease, Redis time in milliseconds, token per listed lease key (0 when
-- not held), number of other indexed lease keys, each such key and its score, then every group key the session
-- joined}. The caller extends the other indexed keys and the groups next.
local sid = ARGV[1]
local lease = tonumber(ARGV[2])
-- PTTL answers 0 in the millisecond the session expires, while Redis still keeps the key (it drops a key only once
-- now > expiry): PEXPIRE alone would revive it. Acquire and join read the same boundary as expired.
if redis.call('PTTL', KEYS[1]) <= 0 then
    return { -1 }
end
redis.call('PEXPIRE', KEYS[1], lease)
redis.call('HSET', KEYS[1], 'lease', lease)
redis.call('PEXPIRE', KEYS[2], lease)
redis.call('PEXPIRE', KEYS[3], lease)
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)

local reply = { lease, now }
local listed = {}
for i = 4, #KEYS do
    listed[KEYS[i]] = true
    local state = redis.call('HMGET', KEYS[i], 'session', 'token', 'expires')
    local expires = tonumber(state[3] or '0')
    if state[1] == sid and expires > now then
        if now + lease > expires then
            redis.call('HSET', KEYS[i], 'expires', now + lease)
        end
        reply[#reply + 1] = tonumber(state[2])
    else
        redis.call('ZREM', KEYS[2], KEYS[i])
        reply[#reply + 1] = 0
    end
end

local indexed = redis.call('ZRANGE', KEYS[2], 0, -1, 'WITHSCORES')
local others = {}
for i = 1, #indexed, 2 do
    if not listed[indexed[i]] then
        others[#others + 1] = indexed[i]
        others[#others + 1] = indexed[i + 1]
    end
end
reply[#reply + 1] = #others / 2
for _, value in ipairs(others) do
    reply[#reply + 1] = value
end
for _, group in ipairs(redis.call('SMEMBERS', KEYS[3])) do
    reply[#reply + 1] = group
end
return reply
