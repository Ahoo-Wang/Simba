-- KEYS[1]: lease key `simba:{mutex}`.
-- ARGV[1]: contenderId; ARGV[2]: lease length in milliseconds (ttl + transition).
-- Renews the lease only when held by contenderId.
-- Returns {ownerId, remaining lease in milliseconds}; {'', 0} when there is no owner.
local mutexKey = KEYS[1];
local contenderId = ARGV[1];
local lease = ARGV[2];

local ownerId = redis.call('get', mutexKey)
if not ownerId then
    return { '', 0 };
end
if ownerId == contenderId then
    redis.call('set', mutexKey, contenderId, 'xx', 'px', lease)
    return { contenderId, tonumber(lease) };
end
return { ownerId, redis.call('pttl', mutexKey) };
