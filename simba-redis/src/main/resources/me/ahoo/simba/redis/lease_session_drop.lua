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

-- Ends a session (ADR 0010 §7.2), last step: drops its lease and group indexes once their leases and memberships
-- were freed.
-- KEYS[1]: lease index; KEYS[2]: group index.
-- Returns {number of keys deleted}.
return { redis.call('DEL', KEYS[1], KEYS[2]) }
