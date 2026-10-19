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

-- Removes a session from groups (ADR 0010 §7.2).
-- KEYS: group sorted sets `simba:group:{group}`. ARGV[1]: session id.
-- Returns {number of groups left}.
local left = 0
for i = 1, #KEYS do
    left = left + redis.call('ZREM', KEYS[i], ARGV[1])
end
return { left }
