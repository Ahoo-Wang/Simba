local mutex = KEYS[1];
local contenderId = ARGV[1];
local mutexKey = 'simba:' .. mutex;
local contenderQueueKey = mutexKey .. ":contender";
-- 1. 获取当前锁的 contenderId 锁是否与自己持久的相同（判断当前锁是否是自己锁定的），如果不相同则直接退出，返回释放失败

if redis.call("get", mutexKey) ~= contenderId then
    redis.call("zrem", contenderQueueKey, contenderId)
    return 0;
end

-- 2. 删除当前锁定的资源

local succeed = redis.call("del", mutexKey)

if not succeed then
    return succeed;
end

-- 3.从等待队列里边获取到第一个 contender,并发布锁释放通知

local contenderQueue = redis.call("zrevrange", contenderQueueKey, -1, -1);

if #contenderQueue == 0 then
    return succeed;
end

local nextContender = contenderQueue[1];
redis.call("zrem", contenderQueueKey, nextContender)

local channel = mutexKey .. ":" .. nextContender;
local message = 'released@@' .. contenderId;
redis.call("publish", channel, message)

return succeed;
