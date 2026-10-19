-- Removes a session from groups (ADR 0010 §7.2).
-- KEYS: group sorted sets `simba:group:{group}`. ARGV[1]: session id.
-- Returns {number of groups left}.
local left = 0
for i = 1, #KEYS do
    left = left + redis.call('ZREM', KEYS[i], ARGV[1])
end
return { left }
