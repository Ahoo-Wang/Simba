local mutex = KEYS[1];
local contenderId = ARGV[1];
-- 使用过渡期 ttl+transition
local transition = ARGV[2];
local mutexKey = 'simba:' .. mutex;

-- 获取当前持有者 & ttl
local function getCurrentOwner(mutexKey)
    local ownerId = redis.call("get", mutexKey)
    if ownerId then
        local ttl = redis.call("pttl", mutexKey)
        return ownerId .. '@@' .. ttl;
    end
    return '@@';
end

-- 1. 判断当前持有互斥体的是否为自己
if redis.call("get", mutexKey) ~= contenderId then
    return getCurrentOwner(mutexKey)
end

if redis.call("set", mutexKey, contenderId, 'xx', 'px', transition) then
    return contenderId .. '@@' .. transition;
else
    return getCurrentOwner(mutexKey)
end
