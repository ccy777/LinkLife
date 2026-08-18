-- 订单关闭 Redis 库存幂等补偿脚本（Stage 3E 017F-R1）
-- KEYS[1]=stockKey  transaction:seckill:stock:{voucherId}
-- KEYS[2]=markerKey transaction:order:close:comp:{orderId}
-- ARGV[1]=orderId ARGV[2]=userId ARGV[3]=voucherId ARGV[4]=eventId
-- ARGV[5]=businessKey ARGV[6]=handledAt ARGV[7]=eventVersion
-- 返回码：0 APPLIED；1 ALREADY_APPLIED；
--         10 INVALID_ARGUMENT；11 STOCK_KEY_MISSING；12 STOCK_KEY_TYPE_INVALID；
--         13 STOCK_VALUE_INVALID；14 MARKER_KEY_TYPE_INVALID；15 MARKER_CORRUPT；
--         16 MARKER_IDENTITY_CONFLICT；20 STOCK_INCREMENT_FAILED；
--         21 MARKER_WRITE_FAILED_ROLLED_BACK；22 MARKER_WRITE_FAILED_ROLLBACK_FAILED

-- 1.参数数量与格式预检（任何写操作之前）
-- 严格要求 KEYS 恰好 2 个、ARGV 恰好 7 个，缺失/多余一律 INVALID_ARGUMENT
if #KEYS ~= 2 or #ARGV ~= 7 then
    return 10
end

local stockKey = KEYS[1]
local markerKey = KEYS[2]
local orderId = ARGV[1]
local userId = ARGV[2]
local voucherId = ARGV[3]
local eventId = ARGV[4]
local businessKey = ARGV[5]
local handledAt = ARGV[6]
local eventVersion = ARGV[7]

-- 规范正整数：只接受 ^[1-9]%d*$，拒绝 "0"/"-1"/"+1"/"01"/"1.5"/"1e3"/"abc" 等非规范格式；
-- 禁止只依赖 tonumber(value) > 0（会接受 "1.5"、"1e3"、"+1"、"01" 等）
local function is_positive_integer(value)
    return value ~= nil
        and string.match(value, '^[1-9]%d*$') ~= nil
end

if stockKey == nil or stockKey == '' or markerKey == nil or markerKey == '' then
    return 10
end
if not is_positive_integer(orderId) or not is_positive_integer(userId)
        or not is_positive_integer(voucherId) then
    return 10
end
if eventId == nil or eventId == '' or businessKey == nil or businessKey == ''
        or handledAt == nil or handledAt == '' then
    return 10
end
if eventVersion ~= '1' then
    return 10
end

-- 2.Key 类型预检
local function key_type(key)
    local reply = redis.call('type', key)
    if type(reply) == 'table' then
        return reply.ok
    end
    return reply
end

local stockType = key_type(stockKey)
local markerType = key_type(markerKey)
if stockType == 'none' then
    return 11
end
if stockType ~= 'string' then
    return 12
end
if markerType ~= 'none' and markerType ~= 'hash' then
    return 14
end

-- 3.库存值预检：规范非负十进制整数
local stockRaw = redis.call('get', stockKey)
if stockRaw == false then
    return 11
end
local canonicalStock = stockRaw == '0' or string.match(stockRaw, '^[1-9]%d*$') ~= nil
if not canonicalStock or string.len(stockRaw) > 18 then
    return 13
end
local stock = tonumber(stockRaw)
if stock == nil or stock < 0 then
    return 13
end

-- 4.marker 已存在：完整身份校验，任何不一致/损坏不修改库存
if markerType == 'hash' then
    local marker = redis.call('hgetall', markerKey)
    local markerState = nil
    local markerEventId = nil
    local markerBusinessKey = nil
    local markerOrderId = nil
    local markerUserId = nil
    local markerVoucherId = nil
    local markerHandledAt = nil
    local markerEventVersion = nil
    for i = 1, #marker, 2 do
        local field = marker[i]
        local value = marker[i + 1]
        if field == 'state' then markerState = value
        elseif field == 'eventId' then markerEventId = value
        elseif field == 'businessKey' then markerBusinessKey = value
        elseif field == 'orderId' then markerOrderId = value
        elseif field == 'userId' then markerUserId = value
        elseif field == 'voucherId' then markerVoucherId = value
        elseif field == 'handledAt' then markerHandledAt = value
        elseif field == 'eventVersion' then markerEventVersion = value
        end
    end
    if markerState ~= 'done' or markerEventId == nil or markerBusinessKey == nil
            or markerOrderId == nil or markerUserId == nil or markerVoucherId == nil
            or markerHandledAt == nil or markerHandledAt == ''
            or markerEventVersion == nil then
        return 15
    end
    -- 身份冲突只比较 eventId/businessKey/orderId/userId/voucherId/eventVersion；
    -- handledAt 是首次真实补偿审计时间，不参与重复事件的身份相等比较
    if markerEventId ~= eventId or markerBusinessKey ~= businessKey
            or markerOrderId ~= orderId or markerUserId ~= userId
            or markerVoucherId ~= voucherId or markerEventVersion ~= eventVersion then
        return 16
    end
    return 1
end

-- 5.首次补偿：INCRBY stock +1，再单次 HSET 完整 marker
local incrReply = redis.pcall('incrby', stockKey, 1)
if type(incrReply) == 'table' and incrReply.err ~= nil then
    return 20
end

local hsetReply = redis.pcall('hset', markerKey,
    'state', 'done',
    'eventId', eventId,
    'businessKey', businessKey,
    'orderId', orderId,
    'userId', userId,
    'voucherId', voucherId,
    'handledAt', handledAt,
    'eventVersion', eventVersion)
if type(hsetReply) == 'table' and hsetReply.err ~= nil then
    -- 6.marker 写入失败：显式回滚 INCRBY -1 并检查结果
    local rollbackReply = redis.pcall('incrby', stockKey, -1)
    if type(rollbackReply) == 'table' and rollbackReply.err ~= nil then
        return 22
    end
    local afterRaw = redis.call('get', stockKey)
    if afterRaw ~= stockRaw then
        return 22
    end
    return 21
end

return 0
