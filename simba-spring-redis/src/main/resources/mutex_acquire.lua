redis.replicate_commands();

local mutex = KEYS[1];
local contenderId = ARGV[1];
-- 使用过渡期 ttl+transition
local transition = ARGV[2];
local mutexKey = 'simba:' .. mutex;
-- 1. 尝试获取锁资源，如果获取成功直接返回
local succeed = redis.call("set", mutexKey, contenderId, 'nx', 'px', transition)

if succeed then
    local message = 'acquired@@' .. contenderId;
    redis.call("publish", mutexKey, message)
    return contenderId..'@@'..transition;
end

-- 2. 将自己加入互斥体等待队列
local contenderQueueKey = mutexKey .. ":contender";

local nowTime = redis.call('time')[1];
redis.call("zadd", contenderQueueKey, 'nx', nowTime, contenderId)
-- 获取当前持有者 & ttl
local ownerId=redis.call("get",mutexKey)
local ttl=redis.call("pttl",mutexKey)
return ownerId..'@@'..ttl;
