-- 1.输入
-- KEYS[1] = login:code:{phone}
-- KEYS[2] = login:code:attempt:{phone}
-- 返回契约：
--   >= 1 = 本次错误后的累计尝试次数
--   -1   = 验证码不存在、已过期或 TTL 非正
--   -2   = attempt 元数据非法
--   -3   = attempt TTL 设置未成功
local codeKey = KEYS[1]
local attemptKey = KEYS[2]

-- 2.读取验证码实际剩余毫秒（PTTL）
local remainingTtl = redis.call('pttl', codeKey)
if remainingTtl <= 0 then
    return -1
end

-- 3.读取并校验现有 attempt 原始字符串（防止 INCR 类型或溢出错误）
local attemptRaw = redis.call('get', attemptKey)
if attemptRaw ~= false then
    local canonicalAttempt = attemptRaw == '0' or string.match(attemptRaw, '^[1-9]%d*$') ~= nil
    if not canonicalAttempt then
        return -2
    end
    local existing = tonumber(attemptRaw)
    if existing == nil or existing > 1000000 then
        return -2
    end
end

-- 4.原子增加错误尝试次数（INCR）
local attempts = redis.call('incr', attemptKey)

-- 5.继承验证码实际剩余 TTL（PEXPIRE）
local ttlSet = redis.call('pexpire', attemptKey, remainingTtl)
if ttlSet ~= 1 then
    -- PEXPIRE 未成功：删除 attempt Key 并返回 -3，避免无 TTL 计数 Key
    redis.call('del', attemptKey)
    return -3
end

-- 6.返回累计尝试次数
return attempts
