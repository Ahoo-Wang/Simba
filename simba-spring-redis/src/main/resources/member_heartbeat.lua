-- KEYS[1]: members of the group `simba:{group}:members`, a sorted set scored by expiry (Redis time, ms).
-- ARGV[1]: memberId, ARGV[2]: ttl in milliseconds.
-- Prunes expired members, then joins or refreshes memberId until now + ttl. The set itself expires once its
-- longest-lived member would have. Returns 1.
local membersKey = KEYS[1];
local memberId = ARGV[1];
local ttl = tonumber(ARGV[2]);
local time = redis.call('time');
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000);

redis.call('zremrangebyscore', membersKey, '-inf', now)
redis.call('zadd', membersKey, now + ttl, memberId)
if redis.call('pttl', membersKey) < ttl then
    redis.call('pexpire', membersKey, ttl)
end
return 1;
