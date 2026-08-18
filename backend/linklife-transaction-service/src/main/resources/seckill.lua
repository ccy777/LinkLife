-- 1.参数防御：ARGV[1]-ARGV[5] 必须存在且非空，缺失或非法统一返回 6，不抛 Lua runtime error
local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local currentTimeRaw = ARGV[4]
local submissionTtlRaw = ARGV[5]
if voucherId == nil or voucherId == '' or userId == nil or userId == '' or orderId == nil or orderId == '' or submissionTtlRaw == nil or submissionTtlRaw == '' then
    return 6
end
local currentTime = tonumber(currentTimeRaw)
if currentTime == nil then
    return 6
end
local submissionTtlSeconds = tonumber(submissionTtlRaw)
if submissionTtlSeconds == nil or submissionTtlSeconds <= 0 then
    return 6
end

-- 2.数据key
local stockKey = 'transaction:seckill:stock:' .. voucherId
local orderKey = 'transaction:seckill:order:' .. voucherId
local beginKey = 'transaction:seckill:begin:' .. voucherId
local endKey = 'transaction:seckill:end:' .. voucherId
local streamKey = 'transaction:stream.orders'
local submissionKey = 'transaction:order:submission:' .. orderId

-- 3.TYPE 解析 helper：RESP2 下 TYPE 是 status reply，在 Lua 中是含 ok 字段的 table；
-- RESP3 下是普通字符串，两种形态统一解析为字符串，禁止直接拿 TYPE 原始返回与字符串比较
local function key_type(key)
    local reply = redis.call('type', key)
    if type(reply) == 'table' then
        return reply.ok
    end
    return reply
end

-- 4.类型预检（任何 GET/SISMEMBER/写操作之前；错误类型统一返回 6）
local stockType = key_type(stockKey)
local beginType = key_type(beginKey)
local endType = key_type(endKey)
local orderType = key_type(orderKey)
local streamType = key_type(streamKey)
-- stock/begin/end：none 由后续元数据检查返回 3（未初始化），string 继续，其他类型返回 6
if (stockType ~= 'none' and stockType ~= 'string')
        or (beginType ~= 'none' and beginType ~= 'string')
        or (endType ~= 'none' and endType ~= 'string') then
    return 6
end
-- order：none（未创建）或 set 合法
if orderType ~= 'none' and orderType ~= 'set' then
    return 6
end
-- stream：none（未创建）或 stream 合法
if streamType ~= 'none' and streamType ~= 'stream' then
    return 6
end
-- submission：none（未创建）或 hash 合法
local submissionType = key_type(submissionKey)
if submissionType ~= 'none' and submissionType ~= 'hash' then
    return 6
end
-- 4.2 既有提交状态保护：任何业务写命令（INCRBY/SADD/XADD/HSET）之前，
-- 若 transaction:order:submission:{orderId} 已存在则返回 6，禁止覆盖既有 Hash（订单 ID 碰撞/人工残留/异常数据）
if redis.call('exists', submissionKey) == 1 then
    return 6
end

-- 5.元数据有效性检查（stock/begin/end 任一 Key 缺失或数字非法返回 3，不得回退为 6）
local stockRaw = redis.call('get', stockKey)
local beginTime = tonumber(redis.call('get', beginKey))
local endTime = tonumber(redis.call('get', endKey))
if stockRaw == false or beginTime == nil or endTime == nil then
    return 3
end
-- 库存必须是规范的非负十进制整数文本（拒绝小数、指数、负号等非规范格式），且数值在 Java Integer 范围 0..2147483647 内
local canonicalStock = stockRaw == '0' or string.match(stockRaw, '^[1-9]%d*$') ~= nil
if not canonicalStock then
    return 3
end
if string.len(stockRaw) > 10 then
    return 3
end
local stock = tonumber(stockRaw)
if stock == nil or stock > 2147483647 then
    return 3
end
if beginTime > endTime then
    return 3
end

-- 6.时间窗口检查（必须发生在任何写操作之前）
if currentTime < beginTime then
    return 4
end
if currentTime > endTime then
    return 5
end

-- 7.库存检查
if stock <= 0 then
    return 1
end

-- 8.重复下单检查
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

-- 9.redis.pcall 错误识别：error reply 在 Lua 中是含 err 字段的 table
local function is_error(reply)
    return type(reply) == 'table' and reply.err ~= nil
end

-- 10.写入顺序（017J-A-R1）：INCRBY -> SADD -> HSET submission -> EXPIRE submission -> XADD；
-- 写前状态冻结：stockRaw、资格 membership=0、submission 不存在；
-- 任一步失败执行显式回滚并统一验证后置条件（库存/资格/submission），全部 pcall 与必要返回值检查；
-- XADD 只以 error reply 判失败：非 error 即视为消息已成功建立并返回 0。
local function rollback_verify()
    local afterStock = redis.call('get', stockKey)
    local afterMember = redis.call('sismember', orderKey, userId)
    local submissionExists = redis.call('exists', submissionKey)
    return afterStock == stockRaw and afterMember == 0 and submissionExists == 0
end

local incrReply = redis.pcall('incrby', stockKey, -1)
if is_error(incrReply) or tonumber(incrReply) ~= stock - 1 then
    return 6
end
local saddReply = redis.pcall('sadd', orderKey, userId)
if is_error(saddReply) or saddReply ~= 1 then
    -- SADD 失败或未新增成员：回滚库存；早期失败至少验证库存与资格（无 submission 写入）
    local compIncr = redis.pcall('incrby', stockKey, 1)
    local afterStock = redis.call('get', stockKey)
    local afterMember = redis.call('sismember', orderKey, userId)
    if is_error(compIncr) or afterStock ~= stockRaw or afterMember ~= 0 then
        return 6
    end
    return 6
end
local hsetReply = redis.pcall('hset', submissionKey,
    'state', 'ACCEPTED',
    'userId', userId,
    'voucherId', voucherId,
    'message', '订单已受理，等待处理',
    'updatedAt', tostring(currentTime))
if is_error(hsetReply) then
    -- HSET 失败：回滚库存与已购集合，并统一验证后置条件
    local compIncr = redis.pcall('incrby', stockKey, 1)
    local compSrem = redis.pcall('srem', orderKey, userId)
    if is_error(compIncr) or is_error(compSrem) or not rollback_verify() then
        return 6
    end
    return 6
end
local expireReply = redis.pcall('expire', submissionKey, submissionTtlSeconds)
if is_error(expireReply) or expireReply ~= 1 then
    -- EXPIRE 失败或未设置成功：回滚库存、已购集合与 submission Hash，并统一验证
    local compIncr = redis.pcall('incrby', stockKey, 1)
    local compSrem = redis.pcall('srem', orderKey, userId)
    local compDel = redis.pcall('del', submissionKey)
    if is_error(compIncr) or is_error(compSrem) or is_error(compDel) or not rollback_verify() then
        return 6
    end
    return 6
end
local xaddReply = redis.pcall('xadd', streamKey, '*', 'userId', userId, 'voucherId', voucherId, 'id', orderId)
if is_error(xaddReply) then
    -- XADD 失败（error reply）：消息未建立，回滚库存、已购集合与 submission Hash，并统一验证
    local compIncr = redis.pcall('incrby', stockKey, 1)
    local compSrem = redis.pcall('srem', orderKey, userId)
    local compDel = redis.pcall('del', submissionKey)
    if is_error(compIncr) or is_error(compSrem) or is_error(compDel) or not rollback_verify() then
        return 6
    end
    return 6
end
-- XADD 非 error reply 即视为消息已成功建立，返回 0；不得因额外类型判断把成功当成失败
return 0
