-- KEYS[1]: lease key `simba:{mutex}`, also the channel all contenders subscribe to.
-- KEYS[2]: legacy contender queue `simba:{mutex}:contender`, written only by Simba < 3.2; deleted on release.
-- ARGV[1]: contenderId.
-- Releases the lease only when held by contenderId and broadcasts `released` so every live contender
-- contends immediately. Returns 1 when released, 0 otherwise.
local mutexKey = KEYS[1];
local legacyQueueKey = KEYS[2];
local contenderId = ARGV[1];

if redis.call('get', mutexKey) ~= contenderId then
    redis.call('zrem', legacyQueueKey, contenderId)
    return 0;
end

redis.call('del', mutexKey, legacyQueueKey)
redis.call('publish', mutexKey, 'released@@' .. contenderId)
return 1;
