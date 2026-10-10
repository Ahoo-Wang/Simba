-- KEYS[1]: lease key `simba:{mutex}`, also the channel all contenders subscribe to.
-- KEYS[2]: fencing token of the current term `simba:{mutex}:token`; the counter is kept.
-- ARGV[1]: contenderId.
-- Releases the lease only when held by contenderId and broadcasts `released` so every live contender
-- contends immediately. Returns 1 when released, 0 otherwise.
local mutexKey = KEYS[1];
local tokenKey = KEYS[2];
local contenderId = ARGV[1];

if redis.call('get', mutexKey) ~= contenderId then
    return 0;
end

redis.call('del', mutexKey, tokenKey)
redis.call('publish', mutexKey, 'released@@' .. contenderId)
return 1;
