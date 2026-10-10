-- KEYS[1]: lease key `simba:{mutex}`, also the channel announcing acquisitions.
-- KEYS[2]: fencing counter `simba:{mutex}:fence` (never expires).
-- KEYS[3]: fencing token of the current term `simba:{mutex}:token` (expires with the lease).
-- ARGV[1]: contenderId; ARGV[2]: lease length in milliseconds (ttl + transition).
-- Returns {ownerId, remaining lease in milliseconds, fencing token}; {'', 0, 0} when there is no owner.
redis.replicate_commands();

local mutexKey = KEYS[1];
local fenceKey = KEYS[2];
local tokenKey = KEYS[3];
local contenderId = ARGV[1];
local lease = ARGV[2];

if redis.call('set', mutexKey, contenderId, 'nx', 'px', lease) then
    -- A new term: issue the next token atomically with the acquisition.
    local token = redis.call('incr', fenceKey)
    redis.call('set', tokenKey, token, 'px', lease)
    redis.call('publish', mutexKey, 'acquired@@' .. contenderId)
    return { contenderId, tonumber(lease), token };
end

local ownerId = redis.call('get', mutexKey)
if not ownerId then
    return { '', 0, 0 };
end
return { ownerId, redis.call('pttl', mutexKey), tonumber(redis.call('get', tokenKey) or '0') };
