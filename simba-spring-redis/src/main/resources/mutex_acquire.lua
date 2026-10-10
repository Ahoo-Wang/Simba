-- KEYS[1]: lease key `simba:{mutex}`, also the channel announcing acquisitions.
-- ARGV[1]: contenderId; ARGV[2]: lease length in milliseconds (ttl + transition).
-- Returns {ownerId, remaining lease in milliseconds}; {'', 0} when there is no owner.
redis.replicate_commands();

local mutexKey = KEYS[1];
local contenderId = ARGV[1];
local lease = ARGV[2];

if redis.call('set', mutexKey, contenderId, 'nx', 'px', lease) then
    redis.call('publish', mutexKey, 'acquired@@' .. contenderId)
    return { contenderId, tonumber(lease) };
end

local ownerId = redis.call('get', mutexKey)
if not ownerId then
    return { '', 0 };
end
return { ownerId, redis.call('pttl', mutexKey) };
