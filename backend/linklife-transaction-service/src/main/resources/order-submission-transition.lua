-- 订单提交状态原子转换脚本
-- ARGV[1]=orderId、ARGV[2]=userId、ARGV[3]=voucherId、ARGV[4]=targetState、
-- ARGV[5]=message、ARGV[6]=updatedAt（Unix 毫秒）、ARGV[7]=ttlSeconds
-- 返回：0 成功；1 参数非法/未知目标状态；2 身份冲突（fail-closed）；3 已有记录字段损坏
local orderId = ARGV[1]
local userId = ARGV[2]
local voucherId = ARGV[3]
local targetState = ARGV[4]
local message = ARGV[5]
local updatedAt = ARGV[6]
local ttlSeconds = tonumber(ARGV[7])
if orderId == nil or orderId == '' or userId == nil or userId == '' or voucherId == nil or voucherId == '' then
    return 1
end
if targetState == nil or targetState == '' or message == nil then
    return 1
end
-- 目标状态白名单：仅 PROCESSING/PERSISTED/FAILED
if targetState ~= 'PROCESSING' and targetState ~= 'PERSISTED' and targetState ~= 'FAILED' then
    return 1
end
local updatedAtMillis = tonumber(updatedAt)
if updatedAtMillis == nil or ttlSeconds == nil or ttlSeconds <= 0 then
    return 1
end

local submissionKey = 'transaction:order:submission:' .. orderId
local exists = redis.call('exists', submissionKey)

local currentState = nil
local currentMessage = nil
if exists == 1 then
    -- 已有记录完整性校验：所有校验必须发生在任何 HSET/EXPIRE 之前
    currentState = redis.call('hget', submissionKey, 'state')
    local currentUserId = redis.call('hget', submissionKey, 'userId')
    local currentVoucherId = redis.call('hget', submissionKey, 'voucherId')
    currentMessage = redis.call('hget', submissionKey, 'message')
    local currentUpdatedAt = redis.call('hget', submissionKey, 'updatedAt')

    -- 身份字段必须存在且非空；字段缺失/非法视为损坏返回 3
    if currentUserId == nil or currentUserId == false or currentUserId == ''
            or currentVoucherId == nil or currentVoucherId == false or currentVoucherId == '' then
        return 3
    end
    if currentUserId ~= userId or currentVoucherId ~= voucherId then
        return 2
    end
    -- message 必须存在且非空
    if currentMessage == nil or currentMessage == false or currentMessage == '' then
        return 3
    end
    -- updatedAt 必须存在且可解析为数字
    if currentUpdatedAt == nil or currentUpdatedAt == false or tonumber(currentUpdatedAt) == nil then
        return 3
    end
    -- 当前状态白名单：仅 ACCEPTED/PROCESSING/PERSISTED/FAILED；nil/空/UNKNOWN/任意未知值均为损坏
    if currentState == nil or currentState == false or currentState == '' then
        return 3
    end
    if currentState ~= 'ACCEPTED' and currentState ~= 'PROCESSING'
            and currentState ~= 'PERSISTED' and currentState ~= 'FAILED' then
        return 3
    end
end

-- 转换矩阵：PERSISTED 不可回退；不存在记录可直接创建目标状态
local finalState
local finalMessage = message
if targetState == 'PROCESSING' then
    if exists == 1 and currentState == 'FAILED' then
        -- FAILED -> PROCESSING：仅用于 ACK 失败后的重新处理恢复
        finalState = 'PROCESSING'
    elseif exists == 1 and currentState == 'PERSISTED' then
        -- PERSISTED -> PROCESSING 不允许回退，保持 PERSISTED
        finalState = 'PERSISTED'
        finalMessage = currentMessage
    else
        -- 不存在创建 / ACCEPTED -> PROCESSING / PROCESSING 幂等
        finalState = 'PROCESSING'
    end
elseif targetState == 'PERSISTED' then
    if exists == 1 and currentState == 'PERSISTED' then
        -- PERSISTED 幂等，message 保持原值
        finalMessage = currentMessage
    end
    finalState = 'PERSISTED'
elseif targetState == 'FAILED' then
    if exists == 1 and currentState == 'PERSISTED' then
        -- PERSISTED -> FAILED 不允许回退，保持 PERSISTED
        finalState = 'PERSISTED'
        finalMessage = currentMessage
    else
        -- 不存在创建 / ACCEPTED/PROCESSING/FAILED -> FAILED
        finalState = 'FAILED'
    end
else
    return 1
end

redis.call('hset', submissionKey,
    'state', finalState,
    'userId', userId,
    'voucherId', voucherId,
    'message', finalMessage,
    'updatedAt', tostring(updatedAtMillis))
redis.call('expire', submissionKey, ttlSeconds)
return 0
