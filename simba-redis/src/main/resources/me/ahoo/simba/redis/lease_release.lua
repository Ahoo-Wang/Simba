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
