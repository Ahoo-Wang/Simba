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

-- Adds a session to groups (ADR 0010 §7.2).
-- ARGV[1]: '1' when KEYS start with the session keys (session hash, group index), else '0'; ARGV[2]: session id;
-- ARGV[3]: without the session keys, how long the memberships last in milliseconds (one full session lease);
-- ARGV[4..]: with the session
-- keys, the group keys to record in the group index (keep-alive extends them, closing the session leaves them).
-- KEYS: [session keys,] group sorted sets `simba:group:{group}`, scored by expiry in Redis time.
-- A membership lasts as long as the session does now: its PTTL, or ARGV[3] on a Cluster.
-- Returns {-1} when the session expired; else {remaining, session lease}.
local withSession = ARGV[1] == '1'
local sid = ARGV[2]
local ttl = tonumber(ARGV[3])
local lease = ttl

local first = 1
if withSession then
    ttl = redis.call('PTTL', KEYS[1])
    if ttl <= 0 then
        return { -1 }
    end
    lease = tonumber(redis.call('HGET', KEYS[1], 'lease') or ttl)
    if #ARGV >= 4 then
        redis.call('SADD', KEYS[2], unpack(ARGV, 4))
        redis.call('PEXPIRE', KEYS[2], ttl)
    end
    first = 3
end

local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
for i = first, #KEYS do
    redis.call('ZADD', KEYS[i], now + ttl, sid)
end
return { ttl, lease }
