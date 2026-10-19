-- Deletes a session (ADR 0010 §7.2); the caller then frees its leases and memberships.
-- KEYS[1]: session hash; KEYS[2]: lease index; KEYS[3]: group index.
-- Returns {number of lease keys, the lease keys, then the group keys} recorded for the session.
local leases = redis.call('SMEMBERS', KEYS[2])
local groups = redis.call('SMEMBERS', KEYS[3])
redis.call('DEL', KEYS[1], KEYS[2], KEYS[3])
local reply = { #leases }
for _, key in ipairs(leases) do
    reply[#reply + 1] = key
end
for _, key in ipairs(groups) do
    reply[#reply + 1] = key
end
return reply
