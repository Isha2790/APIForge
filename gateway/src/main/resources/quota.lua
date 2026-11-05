-- Atomic daily + monthly quota. KEYS[1] = daily counter, KEYS[2] = monthly counter.
-- ARGV: dailyLimit, monthlyLimit (0 = unlimited), dailyTtlSeconds, monthlyTtlSeconds.
-- Returns { code, dailyRemaining, monthlyRemaining }; code 0 = ok, 1 = daily exceeded, 2 = monthly exceeded.
-- A request that is refused does not consume quota. remaining = -1 means unlimited.
local dailyLimit = tonumber(ARGV[1])
local monthlyLimit = tonumber(ARGV[2])
local daily = tonumber(redis.call('GET', KEYS[1]) or '0')
local monthly = tonumber(redis.call('GET', KEYS[2]) or '0')

local function remaining(limit, used)
  if limit > 0 then return math.max(0, limit - used) end
  return -1
end

if dailyLimit > 0 and daily >= dailyLimit then
  return { 1, 0, remaining(monthlyLimit, monthly) }
end
if monthlyLimit > 0 and monthly >= monthlyLimit then
  return { 2, remaining(dailyLimit, daily), 0 }
end

daily = redis.call('INCR', KEYS[1])
if daily == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3])) end
monthly = redis.call('INCR', KEYS[2])
if monthly == 1 then redis.call('EXPIRE', KEYS[2], tonumber(ARGV[4])) end
return { 0, remaining(dailyLimit, daily), remaining(monthlyLimit, monthly) }
