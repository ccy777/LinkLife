-- 订单创建失败 Redis 补偿脚本（Stage 3 017J-A）
-- KEYS[1]=stockKey          transaction:seckill:stock:{voucherId}
-- KEYS[2]=qualificationKey  transaction:seckill:order:{voucherId}
-- KEYS[3]=markerKey         transaction:order:create:comp:{orderId}
-- ARGV[1]=orderId ARGV[2]=userId ARGV[3]=voucherId
-- ARGV[4]=mode ARGV[5]=existingOrderId ARGV[6]=handledAt ARGV[7]=version
-- 返回码：0 APPLIED；1 ALREADY_APPLIED；
-- 10 INVALID_ARGUMENT；11 STOCK_KEY_MISSING；12 STOCK_KEY_TYPE_INVALID；13 STOCK_VALUE_INVALID；
-- 14 QUALIFICATION_KEY_TYPE_INVALID；15 MARKER_KEY_TYPE_INVALID；16 MARKER_CORRUPT；
-- 17 MARKER_IDENTITY_CONFLICT；20 STOCK_INCREMENT_FAILED；
-- 21 QUALIFICATION_REMOVE_FAILED_ROLLED_BACK；22 QUALIFICATION_REMOVE_FAILED_ROLLBACK_FAILED；
-- 23 MARKER_WRITE_FAILED_ROLLED_BACK；24 MARKER_WRITE_FAILED_ROLLBACK_FAILED

-- 1.参数数量与格式预检（任何写操作之前）
if #KEYS ~= 3 or #ARGV ~= 7 then
    return 10
end

local stockKey = KEYS[1]
local qualificationKey = KEYS[2]
local markerKey = KEYS[3]
local orderId = ARGV[1]
local userId = ARGV[2]
local voucherId = ARGV[3]
local mode = ARGV[4]
local existingOrderId = ARGV[5]
local handledAt = ARGV[6]
local version = ARGV[7]

local function is_positive_integer(value)
    return value ~= nil
        and string.match(value, '^[1-9]%d*$') ~= nil
end

if stockKey == nil or stockKey == '' or qualificationKey == nil or qualificationKey == ''
        or markerKey == nil or markerKey == '' then
    return 10
end
if not is_positive_integer(orderId) or not is_positive_integer(userId)
        or not is_positive_integer(voucherId) then
    return 10
end
if mode ~= 'RESTORE_STOCK_AND_RELEASE_QUALIFICATION'
        and mode ~= 'RESTORE_STOCK_KEEP_QUALIFICATION' then
    return 10
end
-- mode 一致性：释放资格必须没有已有订单（existingOrderId=0）；保留资格必须携带已有订单
if mode == 'RESTORE_STOCK_AND_RELEASE_QUALIFICATION' then
    if existingOrderId ~= '0' then
        return 10
    end
else
    if not is_positive_integer(existingOrderId) then
        return 10
    end
end
if handledAt == nil or handledAt == '' then
    return 10
end
if version ~= '1' then
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
local qualificationType = key_type(qualificationKey)
local markerType = key_type(markerKey)
if stockType == 'none' then
    return 11
end
if stockType ~= 'string' then
    return 12
end
if qualificationType ~= 'none' and qualificationType ~= 'set' then
    return 14
end
if markerType ~= 'none' and markerType ~= 'hash' then
    return 15
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

-- 3.1 记录资格写前 membership（任何写操作之前）：0=不存在、1=存在，异常值 fail-closed
local qualificationWasMember = redis.call('sismember', qualificationKey, userId)
if qualificationWasMember ~= 0 and qualificationWasMember ~= 1 then
    return 10
end

-- 4.marker 已存在：完整身份校验，任何不一致/损坏不修改库存
if markerType == 'hash' then
    local marker = redis.call('hgetall', markerKey)
    local markerState = nil
    local markerOrderId = nil
    local markerUserId = nil
    local markerVoucherId = nil
    local markerMode = nil
    local markerExistingOrderId = nil
    local markerHandledAt = nil
    local markerVersion = nil
    for i = 1, #marker, 2 do
        local field = marker[i]
        local value = marker[i + 1]
        if field == 'state' then markerState = value
        elseif field == 'orderId' then markerOrderId = value
        elseif field == 'userId' then markerUserId = value
        elseif field == 'voucherId' then markerVoucherId = value
        elseif field == 'mode' then markerMode = value
        elseif field == 'existingOrderId' then markerExistingOrderId = value
        elseif field == 'handledAt' then markerHandledAt = value
        elseif field == 'version' then markerVersion = value
        end
    end
    if markerState ~= 'done' or markerOrderId == nil or markerUserId == nil
            or markerVoucherId == nil or markerMode == nil or markerExistingOrderId == nil
            or markerHandledAt == nil or markerHandledAt == '' or markerVersion == nil then
        return 16
    end
    if markerOrderId ~= orderId or markerUserId ~= userId or markerVoucherId ~= voucherId
            or markerMode ~= mode or markerExistingOrderId ~= existingOrderId
            or markerVersion ~= version then
        return 17
    end
    return 1
end

-- 5.首次补偿：INCRBY stock +1
local function is_error(reply)
    return type(reply) == 'table' and reply.err ~= nil
end

-- 回滚辅助：将资格恢复为写前 membership（写前存在则 SADD 确保存在；写前不存在则 SREM 确保移除）
local function restore_qualification()
    if qualificationWasMember == 1 then
        local restored = redis.pcall('sadd', qualificationKey, userId)
        return not is_error(restored)
    end
    local restored = redis.pcall('srem', qualificationKey, userId)
    return not is_error(restored)
end

-- 回滚后置条件验证：库存精确恢复原值，资格 membership 恢复写前原值
local function verify_restored()
    local afterStock = redis.call('get', stockKey)
    local afterMember = redis.call('sismember', qualificationKey, userId)
    return afterStock == stockRaw and afterMember == qualificationWasMember
end

local incrReply = redis.pcall('incrby', stockKey, 1)
if is_error(incrReply) then
    return 20
end

-- 6.模式 1（无 MySQL 订单）：释放资格 SREM；模式 2（已有其他订单）：不 SREM
local removedQualification = false
if mode == 'RESTORE_STOCK_AND_RELEASE_QUALIFICATION' then
    local sremReply = redis.pcall('srem', qualificationKey, userId)
    if is_error(sremReply) or (sremReply ~= 0 and sremReply ~= 1) then
        -- SREM 失败或非法返回：显式回滚库存 -1，资格恢复写前 membership，并完整验证
        local rollbackReply = redis.pcall('incrby', stockKey, -1)
        local restored = restore_qualification()
        local rollbackOk = not is_error(rollbackReply) and restored
        if not rollbackOk or not verify_restored() then
            return 22
        end
        return 21
    end
    -- SREM 返回 1：确实删除了成员；返回 0：原本不存在，未删除任何内容
    removedQualification = (sremReply == 1)
end

-- 7.HSET 单次写入完整 marker
local hsetReply = redis.pcall('hset', markerKey,
    'state', 'done',
    'orderId', orderId,
    'userId', userId,
    'voucherId', voucherId,
    'mode', mode,
    'existingOrderId', existingOrderId,
    'handledAt', handledAt,
    'version', version)
if is_error(hsetReply) then
    -- 8.marker 写入失败：回滚库存 -1；只有写前存在且实际删除过才 SADD 恢复；完整验证
    local rollbackReply = redis.pcall('incrby', stockKey, -1)
    local rollbackSadd = nil
    if qualificationWasMember == 1 and removedQualification then
        rollbackSadd = redis.pcall('sadd', qualificationKey, userId)
    end
    local rollbackOk = not is_error(rollbackReply)
            and (not removedQualification or not is_error(rollbackSadd))
    if not rollbackOk or not verify_restored() then
        return 24
    end
    return 23
end

return 0
