-- One token = one new admitted work request, not one provider token or bill.
local reserved = redis.call('GET', KEYS[3])
if reserved then
  if reserved == ARGV[5] then return {1, 0} end
  return {-1, 0}
end
local time = redis.call('TIME')
local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
local function bucket(key, capacity, interval)
  local saved = redis.call('HMGET', key, 'tokens', 'at')
  local tokens = tonumber(saved[1]) or capacity
  local at = tonumber(saved[2]) or now
  tokens = math.min(capacity, tokens + math.max(0, now-at)/interval)
  return tokens
end
local uc, ui, gc, gi = tonumber(ARGV[1]), tonumber(ARGV[2]), tonumber(ARGV[3]), tonumber(ARGV[4])
local user = bucket(KEYS[1], uc, ui)
local global = bucket(KEYS[2], gc, gi)
if user < 1 or global < 1 then
  return {0, math.max(1, math.ceil(math.max((1-user)*ui, (1-global)*gi)))}
end
redis.call('HSET', KEYS[1], 'tokens', tostring(user-1), 'at', tostring(now))
redis.call('PEXPIRE', KEYS[1], math.ceil(uc*ui*2+60000))
redis.call('HSET', KEYS[2], 'tokens', tostring(global-1), 'at', tostring(now))
redis.call('PEXPIRE', KEYS[2], math.ceil(gc*gi*2+60000))
-- A DB failure does not refund a token; retrying this identity reuses admission.
redis.call('SET', KEYS[3], ARGV[5], 'PX', 86400000)
return {1, 0}
