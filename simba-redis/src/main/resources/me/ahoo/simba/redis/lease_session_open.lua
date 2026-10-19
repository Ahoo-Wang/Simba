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

-- Opens a session (ADR 0010 §7.2). The session hash records the session's lease; its PX is the session's liveness.
-- KEYS[1]: session hash `simba:session:{sid}`.
-- ARGV[1]: lease in milliseconds; ARGV[2..]: metadata as field, value pairs.
-- Returns {lease}.
redis.call('HSET', KEYS[1], 'lease', ARGV[1])
for i = 2, #ARGV, 2 do
    redis.call('HSET', KEYS[1], 'meta:' .. ARGV[i], ARGV[i + 1])
end
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return { tonumber(ARGV[1]) }
