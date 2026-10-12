-- KEYS[1]: members of the group `simba:{group}:members`, a sorted set scored by expiry (Redis time, ms).
-- Returns the members whose membership has not expired, in any order.
local membersKey = KEYS[1];
local time = redis.call('time');
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000);

return redis.call('zrangebyscore', membersKey, '(' .. now, '+inf');
