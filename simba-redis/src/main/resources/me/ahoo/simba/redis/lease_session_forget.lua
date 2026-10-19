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

-- Drops lease keys from a session's lease index (ADR 0010 §7.2) on a Redis Cluster, where an acquisition records its
-- key in one call and takes the lease in another.
-- KEYS[1]: lease index. ARGV: lease key, score pairs as keep-alive read them.
-- The caller passes only keys the session did not hold, read at least one lease plus twice the allowed clock skew
-- after their last attempt began: an attempt's lease call grants nothing after its session call's time plus the lease
-- plus that skew, so none can still take them. A key whose score changed was tried again since and stays.
-- Returns {number of keys dropped}.
local dropped = 0
for i = 1, #ARGV, 2 do
    if redis.call('ZSCORE', KEYS[1], ARGV[i]) == ARGV[i + 1] then
        dropped = dropped + redis.call('ZREM', KEYS[1], ARGV[i])
    end
end
return { dropped }
