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

-- Ends a session (ADR 0010 §7.2), first of three steps that are safe to repeat: this one deletes the session hash
-- (so keep-alive, acquire and join fail from now on) but keeps the lease and group indexes for one more lease; the
-- caller then frees the indexed leases and memberships, and finally drops the indexes (lease_session_drop).
-- KEYS[1]: session hash; KEYS[2]: lease index; KEYS[3]: group index.
-- Returns {number of lease keys, the lease keys, then the group keys} recorded for the session.
local lease = redis.call('HGET', KEYS[1], 'lease')
redis.call('DEL', KEYS[1])
if lease then
    redis.call('PEXPIRE', KEYS[2], lease)
    redis.call('PEXPIRE', KEYS[3], lease)
end
local leases = redis.call('ZRANGE', KEYS[2], 0, -1)
local reply = { #leases }
for _, key in ipairs(leases) do
    reply[#reply + 1] = key
end
for _, key in ipairs(redis.call('SMEMBERS', KEYS[3])) do
    reply[#reply + 1] = key
end
return reply
