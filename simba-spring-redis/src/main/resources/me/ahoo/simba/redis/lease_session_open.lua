-- Opens a session (ADR 0010 §7.2). The session hash only records the session; lease decisions never read it.
-- KEYS[1]: session hash `simba:session:{sid}`.
-- ARGV[1]: lease in milliseconds; ARGV[2..]: metadata as field, value pairs.
-- Returns {lease}.
redis.call('HSET', KEYS[1], 'lease', ARGV[1])
for i = 2, #ARGV, 2 do
    redis.call('HSET', KEYS[1], 'meta:' .. ARGV[i], ARGV[i + 1])
end
redis.call('PEXPIRE', KEYS[1], ARGV[1])
return { tonumber(ARGV[1]) }
