-- KEYS[1]: lease key `simba:{mutex}`.
-- KEYS[2]: fencing token of the current term `simba:{mutex}:token`.
-- ARGV[1]: contenderId; ARGV[2]: lease length in milliseconds (ttl + transition).
-- Renews the lease, keeping its fencing token, only when held by contenderId.
-- Returns {ownerId, remaining lease in milliseconds, fencing token}; {'', 0, 0} when there is no owner.
local mutexKey = KEYS[1];
local tokenKey = KEYS[2];
local contenderId = ARGV[1];
local lease = ARGV[2];

local ownerId = redis.call('get', mutexKey)
if not ownerId then
    return { '', 0, 0 };
end
local token = tonumber(redis.call('get', tokenKey) or '0')
if ownerId == contenderId then
    redis.call('set', mutexKey, contenderId, 'xx', 'px', lease)
    redis.call('pexpire', tokenKey, lease)
    return { contenderId, tonumber(lease), token };
end
return { ownerId, redis.call('pttl', mutexKey), token };
