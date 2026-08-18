-- 秒杀券 Redis 原子初始化脚本（Stage 3 017J-B）
-- KEYS[1]=stockKey  transaction:seckill:stock:{voucherId}
-- KEYS[2]=beginKey  transaction:seckill:begin:{voucherId}
-- KEYS[3]=endKey    transaction:seckill:end:{voucherId}
-- KEYS[4]=markerKey transaction:seckill:init:marker:{voucherId}
-- ARGV[1]=voucherId ARGV[2]=initialStock ARGV[3]=beginEpochMillis ARGV[4]=endEpochMillis
-- ARGV[5]=eventId ARGV[6]=businessKey ARGV[7]=handledAt ARGV[8]=eventVersion
-- 返回码：0 INITIALIZED；1 ALREADY_INITIALIZED；
-- 10 INVALID_ARGUMENT；11 STOCK_KEY_TYPE_INVALID；12 BEGIN_KEY_TYPE_INVALID；
-- 13 END_KEY_TYPE_INVALID；14 MARKER_KEY_TYPE_INVALID；15 MARKER_CORRUPT；
-- 16 MARKER_IDENTITY_CONFLICT；17 INITIALIZED_STATE_CORRUPT；18 PREEXISTING_STATE_CONFLICT；
-- 20 INIT_WRITE_FAILED_ROLLED_BACK；21 INIT_WRITE_FAILED_ROLLBACK_FAILED

-- 1.参数数量与格式预检（任何写操作之前）
if #KEYS ~= 4 or #ARGV ~= 8 then
    return 10
end

local stockKey = KEYS[1]
local beginKey = KEYS[2]
local endKey = KEYS[3]
local markerKey = KEYS[4]
local voucherId = ARGV[1]
local initialStock = ARGV[2]
local beginEpochMillis = ARGV[3]
local endEpochMillis = ARGV[4]
local eventId = ARGV[5]
local businessKey = ARGV[6]
local handledAt = ARGV[7]
local eventVersion = ARGV[8]

local function is_positive_integer(value)
    return value ~= nil
        and string.match(value, '^[1-9]%d*$') ~= nil
end

local function is_non_negative_integer(value)
    return value ~= nil
        and (value == '0' or string.match(value, '^[1-9]%d*$') ~= nil)
end

if stockKey == nil or stockKey == '' or beginKey == nil or beginKey == ''
        or endKey == nil or endKey == '' or markerKey == nil or markerKey == '' then
    return 10
end
if not is_positive_integer(voucherId) then
    return 10
end
if not is_non_negative_integer(initialStock) then
    return 10
end
if tonumber(initialStock) > 2147483647 then
    return 10
end
if not is_positive_integer(beginEpochMillis) or not is_positive_integer(endEpochMillis) then
    return 10
end
if tonumber(beginEpochMillis) >= tonumber(endEpochMillis) then
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
local beginType = key_type(beginKey)
local endType = key_type(endKey)
local markerType = key_type(markerKey)
if stockType ~= 'none' and stockType ~= 'string' then
    return 11
end
if beginType ~= 'none' and beginType ~= 'string' then
    return 12
end
if endType ~= 'none' and endType ~= 'string' then
    return 13
end
if markerType ~= 'none' and markerType ~= 'hash' then
    return 14
end

local function is_error(reply)
    return type(reply) == 'table' and reply.err ~= nil
end

-- 3.marker 已存在：完整身份与业务 Key 完整性校验
if markerType == 'hash' then
    local marker = redis.call('hgetall', markerKey)
    local mState = nil
    local mVoucherId = nil
    local mInitialStock = nil
    local mBegin = nil
    local mEnd = nil
    local mEventId = nil
    local mBusinessKey = nil
    local mHandledAt = nil
    local mVersion = nil
    for i = 1, #marker, 2 do
        local field = marker[i]
        local value = marker[i + 1]
        if field == 'state' then mState = value
        elseif field == 'voucherId' then mVoucherId = value
        elseif field == 'initialStock' then mInitialStock = value
        elseif field == 'beginEpochMillis' then mBegin = value
        elseif field == 'endEpochMillis' then mEnd = value
        elseif field == 'eventId' then mEventId = value
        elseif field == 'businessKey' then mBusinessKey = value
        elseif field == 'handledAt' then mHandledAt = value
        elseif field == 'eventVersion' then mVersion = value
        end
    end
    if mState ~= 'done' or mVoucherId == nil or mInitialStock == nil or mBegin == nil
            or mEnd == nil or mEventId == nil or mBusinessKey == nil
            or mHandledAt == nil or mHandledAt == '' or mVersion == nil then
        return 15
    end
    if mVoucherId ~= voucherId or mInitialStock ~= initialStock
            or mBegin ~= beginEpochMillis or mEnd ~= endEpochMillis
            or mEventId ~= eventId or mBusinessKey ~= businessKey
            or mVersion ~= eventVersion then
        return 16
    end
    -- 业务 Key 完整性：stock/begin/end 必须存在、string、规范值；
    -- 实时 stock 不要求等于 initialStock（网络不确定重试时订单可能已扣减，不得重置）
    if stockType ~= 'string' or beginType ~= 'string' or endType ~= 'string' then
        return 17
    end
    local stockRaw = redis.call('get', stockKey)
    local beginRaw = redis.call('get', beginKey)
    local endRaw = redis.call('get', endKey)
    if stockRaw == false or beginRaw == false or endRaw == false then
        return 17
    end
    if not is_non_negative_integer(stockRaw) then
        return 17
    end
    if not is_positive_integer(beginRaw) or not is_positive_integer(endRaw) then
        return 17
    end
    if beginRaw ~= beginEpochMillis or endRaw ~= endEpochMillis then
        return 17
    end
    return 1
end

-- 4.marker 不存在：要求 stock/begin/end 全部不存在，任一预存在即冲突，不覆盖不删除
if stockType ~= 'none' or beginType ~= 'none' or endType ~= 'none' then
    return 18
end

-- 5.首次初始化：SET stock -> SET begin -> SET end -> 单次 HSET marker；
-- 任一步失败显式删除本次已创建的 Key，回滚后验证四个 Key 全部不存在
local created = {}
local function rollback_created()
    for i = 1, #created do
        local r = redis.pcall('del', created[i])
        if is_error(r) then
            return false
        end
    end
    return true
end
local function verify_all_absent()
    return redis.call('exists', stockKey) == 0
            and redis.call('exists', beginKey) == 0
            and redis.call('exists', endKey) == 0
            and redis.call('exists', markerKey) == 0
end
local function write_failed()
    if not rollback_created() or not verify_all_absent() then
        return 21
    end
    return 20
end

local setStockReply = redis.pcall('set', stockKey, initialStock)
if is_error(setStockReply) then
    return write_failed()
end
table.insert(created, stockKey)
local setBeginReply = redis.pcall('set', beginKey, beginEpochMillis)
if is_error(setBeginReply) then
    return write_failed()
end
table.insert(created, beginKey)
local setEndReply = redis.pcall('set', endKey, endEpochMillis)
if is_error(setEndReply) then
    return write_failed()
end
table.insert(created, endKey)
local hsetMarkerReply = redis.pcall('hset', markerKey,
    'state', 'done',
    'voucherId', voucherId,
    'initialStock', initialStock,
    'beginEpochMillis', beginEpochMillis,
    'endEpochMillis', endEpochMillis,
    'eventId', eventId,
    'businessKey', businessKey,
    'handledAt', handledAt,
    'eventVersion', eventVersion)
if is_error(hsetMarkerReply) then
    return write_failed()
end

return 0
