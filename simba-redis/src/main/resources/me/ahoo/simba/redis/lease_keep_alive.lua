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

-- Extends the leases and memberships of a session (ADR 0010 §7.2), after lease_session_keep_alive extended the
-- session itself.
-- ARGV[1]: '1' when KEYS[1] is the session's lease index (a single node only), else '0'; ARGV[2]: session id;
-- ARGV[3]: lease in milliseconds; ARGV[4]: number of lease keys; ARGV[5]: Redis time (of the session's node) that
-- nothing is extended beyond: the session's new expiry, plus the allowed clock skew on a Cluster.
-- KEYS: [lease index,] lease keys `simba:lease:{name}`, then group keys `simba:group:{group}`.
-- A lease is extended only while the session holds it and it has not expired, a membership only while it has not
-- expired; nothing expired is ever revived. Neither outlasts ARGV[5]: this call may run long after the session was
-- extended, which may have expired since. With the lease index, a lease the session no longer holds leaves it.
-- Returns {token per lease key (0 when not held)}.
local withIndex = ARGV[1] == '1'
local sid = ARGV[2]
local lease = tonumber(ARGV[3])
local leases = tonumber(ARGV[4])
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local untilTime = math.min(now + lease, tonumber(ARGV[5]))

local first = 1
if withIndex then
    first = 2
end
local reply = {}
for i = first, first + leases - 1 do
    local state = redis.call('HMGET', KEYS[i], 'session', 'token', 'expires')
    local expires = tonumber(state[3] or '0')
    if state[1] == sid and expires > now then
        if untilTime > expires then
            redis.call('HSET', KEYS[i], 'expires', untilTime)
        end
        reply[#reply + 1] = tonumber(state[2])
    else
        if withIndex then
            redis.call('ZREM', KEYS[1], KEYS[i])
        end
        reply[#reply + 1] = 0
    end
end
for i = first + leases, #KEYS do
    local score = redis.call('ZSCORE', KEYS[i], sid)
    if score and tonumber(score) > now and untilTime > tonumber(score) then
        redis.call('ZADD', KEYS[i], untilTime, sid)
    end
end
return reply
