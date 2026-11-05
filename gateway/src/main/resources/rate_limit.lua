-- Atomic token bucket. KEYS[1] = bucket key, ARGV[1] = capacity (= requests per minute).
-- Time comes from Redis itself, so gateway instances with different clocks cannot skew the bucket.
-- Returns { allowed (1/0), tokens left }.
local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local period = 60000
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local entries = redis.call('HMGET', key, 'tokens', 'updated')
local tokens = tonumber(entries[1]) or capacity
local updated = tonumber(entries[2]) or now
local elapsed = math.max(0, now - updated)
tokens = math.min(capacity, tokens + elapsed * capacity / period)
local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end
redis.call('HSET', key, 'tokens', tokens, 'updated', now)
redis.call('PEXPIRE', key, 120000)
return { allowed, math.floor(tokens) }
