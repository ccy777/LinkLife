package com.linklife.trade.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * seckill.lua 静态脚本契约测试：校验脚本文本中的关键结构。
 * 这是脚本契约测试，不是 Redis 集成测试，本任务未在真实 Redis 中执行 Lua。
 */
class SeckillLuaContractTest {

    private String lua() throws Exception {
        return StreamUtils.copyToString(
                new ClassPathResource("seckill.lua").getInputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void typeResultIsParsedThroughHelperOkField() throws Exception {
        String script = lua();

        // TYPE 在 RESP2 下是 status reply（Lua 中为含 ok 字段的 table），必须经 helper 解析
        assertThat(script).contains("local function key_type(key)");
        assertThat(script).contains("local reply = redis.call('type', key)");
        assertThat(script).contains("if type(reply) == 'table' then");
        assertThat(script).contains("return reply.ok");
    }

    @Test
    void rawTypeReplyIsNeverComparedDirectlyWithString() throws Exception {
        String script = lua();

        // 五个类型变量必须全部来自 key_type helper
        assertThat(script).contains("local stockType = key_type(stockKey)");
        assertThat(script).contains("local beginType = key_type(beginKey)");
        assertThat(script).contains("local endType = key_type(endKey)");
        assertThat(script).contains("local orderType = key_type(orderKey)");
        assertThat(script).contains("local streamType = key_type(streamKey)");
        // redis.call('type', ...) 只能出现在 helper 内部，禁止任何直接把原始 reply 与字符串比较
        assertThat(countOccurrences(script, "redis.call('type'")).isEqualTo(1);
        assertThat(script).doesNotContain("redis.call('type', stockKey) ~= 'string'");
        assertThat(script).doesNotContain("redis.call('type', beginKey) ~= 'string'");
        assertThat(script).doesNotContain("redis.call('type', endKey) ~= 'string'");
    }

    @Test
    void missingStockBeginEndKeysReturnThreeNotSix() throws Exception {
        String script = lua();

        // 类型预检必须放行 none（未初始化），由元数据检查返回 3，不得把缺失元数据回退为 6
        assertThat(script).contains("stockType ~= 'none' and stockType ~= 'string'");
        assertThat(script).contains("beginType ~= 'none' and beginType ~= 'string'");
        assertThat(script).contains("endType ~= 'none' and endType ~= 'string'");
        assertThat(script).contains("local stockRaw = redis.call('get', stockKey)");
        assertThat(script).contains("local beginTime = tonumber(redis.call('get', beginKey))");
        assertThat(script).contains("local endTime = tonumber(redis.call('get', endKey))");
        assertThat(script).contains("if stockRaw == false or beginTime == nil or endTime == nil then");
        assertThat(script).contains("return 3");
    }

    @Test
    void wrongKeyTypesReturnSix() throws Exception {
        String script = lua();

        assertThat(script).contains("orderType ~= 'none' and orderType ~= 'set'");
        assertThat(script).contains("streamType ~= 'none' and streamType ~= 'stream'");
        assertThat(script).contains("return 6");
    }

    @Test
    void orderSetAndStreamTypesAreAllowed() throws Exception {
        String script = lua();

        assertThat(script).contains("if orderType ~= 'none' and orderType ~= 'set' then");
        assertThat(script).contains("if streamType ~= 'none' and streamType ~= 'stream' then");
    }

    @Test
    void usesRedisPcallNotPcallRedisCall() throws Exception {
        String script = lua();

        assertThat(script).contains("redis.pcall('incrby', stockKey, -1)");
        assertThat(script).contains("redis.pcall('sadd', orderKey, userId)");
        assertThat(script).contains("redis.pcall('xadd', streamKey");
        assertThat(script).doesNotContain("pcall(redis.call");
    }

    @Test
    void redisPcallErrorReplyIsChecked() throws Exception {
        String script = lua();

        assertThat(script).contains("type(reply) == 'table' and reply.err ~= nil");
        assertThat(script).contains("local function is_error(reply)");
        assertThat(script).contains("if is_error(incrReply) or tonumber(incrReply) ~= stock - 1 then");
        assertThat(script).contains("if is_error(saddReply) or saddReply ~= 1 then");
        assertThat(script).contains("if is_error(xaddReply)");
    }

    @Test
    void compensationResultsAreChecked() throws Exception {
        String script = lua();

        // SADD 补偿：INCRBY +1 的结果必须检查
        assertThat(script).contains("local compIncr = redis.pcall('incrby', stockKey, 1)");
        assertThat(script).contains("if is_error(compIncr) or afterStock ~= stockRaw or afterMember ~= 0 then");
        // XADD 补偿：INCRBY +1 与 SREM 的结果必须同时检查
        assertThat(script).contains("local compSrem = redis.pcall('srem', orderKey, userId)");
        assertThat(script).contains("or not rollback_verify() then");
    }

    @Test
    void argumentDefenseReturnsSixWithoutRuntimeError() throws Exception {
        String script = lua();

        assertThat(script).contains("local voucherId = ARGV[1]");
        assertThat(script).contains("local userId = ARGV[2]");
        assertThat(script).contains("local orderId = ARGV[3]");
        assertThat(script).contains("local currentTimeRaw = ARGV[4]");
        assertThat(script).contains("voucherId == nil or voucherId == ''");
        assertThat(script).contains("userId == nil or userId == ''");
        assertThat(script).contains("orderId == nil or orderId == ''");
        assertThat(script).contains("local currentTime = tonumber(currentTimeRaw)");
        assertThat(script).contains("if currentTime == nil then");
        assertThat(script).contains("return 6");
    }

    @Test
    void allTypeChecksHappenBeforeGetSismemberAndWrites() throws Exception {
        String script = lua();

        int lastTypeCheck = script.indexOf("if streamType ~= 'none' and streamType ~= 'stream' then");
        int firstGet = script.indexOf("redis.call('get'");
        int firstSismember = script.indexOf("redis.call('sismember'");
        int firstWrite = script.indexOf("redis.pcall('incrby'");

        assertThat(lastTypeCheck).isNotNegative();
        assertThat(lastTypeCheck).isLessThan(firstGet);
        assertThat(lastTypeCheck).isLessThan(firstSismember);
        assertThat(lastTypeCheck).isLessThan(firstWrite);
    }

    @Test
    void timeChecksAreBeforeStockMutationAndStreamWrites() throws Exception {
        String script = lua();

        int beginCheck = script.indexOf("currentTime < beginTime");
        int endCheck = script.indexOf("currentTime > endTime");
        int firstWrite = script.indexOf("redis.pcall('incrby'");

        assertThat(beginCheck).isNotNegative();
        assertThat(endCheck).isNotNegative();
        assertThat(beginCheck).isLessThan(firstWrite);
        assertThat(endCheck).isLessThan(firstWrite);
    }

    @Test
    void scriptContainsSixFixedReturnCodes() throws Exception {
        String script = lua();

        for (String code : new String[]{"return 0", "return 1", "return 2", "return 3", "return 4", "return 5"}) {
            assertThat(script).contains(code);
        }
    }

    @Test
    void xaddKeepsUserIdVoucherIdIdFields() throws Exception {
        String script = lua();

        assertThat(script).contains("'userId', userId, 'voucherId', voucherId, 'id', orderId");
        assertThat(script).contains("'transaction:stream.orders'");
    }

    @Test
    void currentTimeBeginEndAndStockAllGoThroughNumberValidation() throws Exception {
        String script = lua();

        assertThat(script).contains("local currentTime = tonumber(currentTimeRaw)");
        assertThat(script).contains("local stockRaw = redis.call('get', stockKey)");
        assertThat(script).contains("local stock = tonumber(stockRaw)");
        assertThat(script).contains("local beginTime = tonumber(redis.call('get', beginKey))");
        assertThat(script).contains("local endTime = tonumber(redis.call('get', endKey))");
        assertThat(script).contains("if currentTime == nil then");
    }

    @Test
    void scriptExplicitlyRejectsDecimalStockText() throws Exception {
        String script = lua();

        assertThat(script).contains("stockRaw == '0' or string.match(stockRaw, '^[1-9]%d*$') ~= nil");
        assertThat(script).contains("小数");
        assertThat(script.indexOf("string.match(stockRaw, '^[1-9]%d*$')"))
                .isLessThan(script.indexOf("redis.pcall('incrby'"));
    }

    @Test
    void scriptExplicitlyRejectsExponentStockText() throws Exception {
        String script = lua();

        assertThat(script).contains("stockRaw == '0' or string.match(stockRaw, '^[1-9]%d*$') ~= nil");
        assertThat(script).contains("指数");
        assertThat(script.indexOf("string.match(stockRaw, '^[1-9]%d*$')"))
                .isLessThan(script.indexOf("redis.pcall('incrby'"));
    }

    @Test
    void scriptExplicitlyRejectsNegativeStockText() throws Exception {
        String script = lua();

        // 规范模式只允许首字符为 1-9（或整体为 "0"），不包含负号
        assertThat(script).contains("string.match(stockRaw, '^[1-9]%d*$')");
        assertThat(script).doesNotContain("%-?");
        assertThat(script.indexOf("string.match(stockRaw, '^[1-9]%d*$')"))
                .isLessThan(script.indexOf("redis.pcall('incrby'"));
    }

    @Test
    void scriptExplicitlyRejectsLeadingZeroStockText() throws Exception {
        String script = lua();

        // 仅 "0" 单独合法，其余前导零文本不匹配 ^[1-9]%d*$
        assertThat(script).contains("stockRaw == '0' or string.match(stockRaw, '^[1-9]%d*$') ~= nil");
        assertThat(script.indexOf("stockRaw == '0'"))
                .isLessThan(script.indexOf("redis.pcall('incrby'"));
    }

    @Test
    void scriptExplicitlyRejectsStockTextLongerThanTenDigits() throws Exception {
        String script = lua();

        assertThat(script).contains("string.len(stockRaw) > 10");
        assertThat(script.indexOf("string.len(stockRaw) > 10"))
                .isLessThan(script.indexOf("redis.pcall('incrby'"));
    }

    @Test
    void scriptLimitsStockToIntegerMaxValue() throws Exception {
        String script = lua();

        assertThat(script).contains("stock > 2147483647");
        assertThat(script.indexOf("stock > 2147483647"))
                .isLessThan(script.indexOf("redis.pcall('incrby'"));
    }

    @Test
    void scriptKeepsZeroStockInInsufficientBranch() throws Exception {
        String script = lua();

        // "0" 通过规范校验后进入 stock <= 0（返回 1），而不是被判元数据非法（返回 3）
        assertThat(script.indexOf("stockRaw == '0'"))
                .isLessThan(script.indexOf("stock <= 0"));
        assertThat(script.indexOf("stock <= 0"))
                .isLessThan(script.indexOf("redis.pcall('incrby'"));
    }

    @Test
    void scriptPrechecksAllKeyTypesBeforeWrites() throws Exception {
        String script = lua();

        assertThat(script).contains("local stockType = key_type(stockKey)");
        assertThat(script).contains("local beginType = key_type(beginKey)");
        assertThat(script).contains("local endType = key_type(endKey)");
        assertThat(script).contains("local orderType = key_type(orderKey)");
        assertThat(script).contains("local streamType = key_type(streamKey)");
        assertThat(script.indexOf("local streamType = key_type(streamKey)"))
                .isLessThan(script.indexOf("redis.pcall('incrby'"));
    }

    @Test
    void allWritesAfterFullValidation() throws Exception {
        String script = lua();

        int lastValidation = Math.max(script.indexOf("return 5"), script.indexOf("return 2"));
        int firstWrite = script.indexOf("redis.pcall('incrby'");
        assertThat(lastValidation).isNotNegative();
        assertThat(lastValidation).isLessThan(firstWrite);
    }

    @Test
    void xaddFailureCompensatesStockAndOrderSet() throws Exception {
        String script = lua();

        // XADD 失败分支内的补偿（INCRBY +1 / SREM）必须出现在 XADD 之后
        int xadd = script.indexOf("redis.pcall('xadd'");
        int incrbyCompensation = script.indexOf("redis.pcall('incrby', stockKey, 1)", xadd);
        int srem = script.indexOf("redis.pcall('srem', orderKey, userId)", xadd);
        assertThat(xadd).isNotNegative();
        assertThat(xadd).isLessThan(incrbyCompensation);
        assertThat(xadd).isLessThan(srem);
    }

    @Test
    void saddFailureCompensatesStock() throws Exception {
        String script = lua();

        assertThat(script.indexOf("redis.pcall('sadd'"))
                .isLessThan(script.indexOf("redis.pcall('incrby', stockKey, 1)"));
    }

    @Test
    void failureCodeSixPresent() throws Exception {
        assertThat(lua()).contains("return 6");
    }

    @Test
    void submissionTtlArgvIsValidatedAsPositiveNumber() throws Exception {
        String script = lua();

        assertThat(script).contains("local submissionTtlRaw = ARGV[5]");
        assertThat(script).contains("submissionTtlRaw == nil or submissionTtlRaw == ''");
        assertThat(script).contains("local submissionTtlSeconds = tonumber(submissionTtlRaw)");
        assertThat(script).contains("submissionTtlSeconds == nil or submissionTtlSeconds <= 0");
        assertThat(script).contains("return 6");
    }

    @Test
    void submissionKeyUsesOrderIdPrefix() throws Exception {
        String script = lua();

        assertThat(script).contains("local submissionKey = 'transaction:order:submission:' .. orderId");
    }

    @Test
    void submissionHashTypeIsPrecheckedBeforeWrites() throws Exception {
        String script = lua();

        assertThat(script).contains("local submissionType = key_type(submissionKey)");
        assertThat(script).contains("submissionType ~= 'none' and submissionType ~= 'hash'");
        int submissionCheck = script.indexOf("submissionType ~= 'none' and submissionType ~= 'hash'");
        int streamCheck = script.indexOf("if streamType ~= 'none' and streamType ~= 'stream' then");
        int firstWrite = script.indexOf("redis.pcall('incrby'");
        assertThat(submissionCheck).isGreaterThan(streamCheck);
        assertThat(submissionCheck).isLessThan(firstWrite);
    }

    @Test
    void acceptedStatusWrittenBeforeXaddAndReturnZero() throws Exception {
        String script = lua();

        assertThat(script).contains("redis.pcall('hset', submissionKey");
        assertThat(script).contains("'state', 'ACCEPTED'");
        assertThat(script).contains("'userId', userId");
        assertThat(script).contains("'voucherId', voucherId");
        assertThat(script).contains("'message',");
        assertThat(script).contains("'updatedAt', tostring(currentTime)");
        assertThat(script).contains("redis.pcall('expire', submissionKey, submissionTtlSeconds)");

        int xadd = script.indexOf("redis.pcall('xadd'");
        int hset = script.indexOf("redis.pcall('hset'");
        int returnZero = script.indexOf("return 0");
        assertThat(xadd).isNotNegative();
        assertThat(hset).isLessThan(xadd);
        assertThat(hset).isLessThan(returnZero);
    }

    @Test
    void submissionStatusWrittenBeforeXaddFailureGuard() throws Exception {
        String script = lua();

        int hset = script.indexOf("redis.pcall('hset'");
        int xaddSuccessGuard = script.indexOf("if is_error(xaddReply) then");
        assertThat(xaddSuccessGuard).isNotNegative();
        assertThat(hset).isLessThan(xaddSuccessGuard);
        assertThat(countOccurrences(script, "'state', 'ACCEPTED'")).isEqualTo(1);
    }

    @Test
    void xaddIsTheLastBusinessWriteBeforeReturnZero() throws Exception {
        String script = lua();

        int xadd = script.indexOf("redis.pcall('xadd'");
        int returnZero = script.indexOf("return 0");
        assertThat(xadd).isNotNegative();
        assertThat(xadd).isLessThan(returnZero);
        // 成功路径在 XADD 之后不得再有其他业务写（HSET/EXPIRE 已前移）
        String tail = script.substring(xadd, returnZero);
        assertThat(tail).doesNotContain("redis.pcall('hset'").doesNotContain("redis.pcall('expire'");
    }

    @Test
    void hsetFailureRollsBackStockAndQualification() throws Exception {
        String script = lua();

        int hsetGuard = script.indexOf("if is_error(hsetReply) then");
        int compIncr = script.indexOf("local compIncr = redis.pcall('incrby', stockKey, 1)", hsetGuard);
        int compSrem = script.indexOf("local compSrem = redis.pcall('srem', orderKey, userId)", hsetGuard);
        assertThat(hsetGuard).isNotNegative();
        assertThat(hsetGuard).isLessThan(compIncr);
        assertThat(hsetGuard).isLessThan(compSrem);
        assertThat(script).contains("if is_error(compIncr) or is_error(compSrem) or not rollback_verify() then");
    }

    @Test
    void expireFailureRollsBackStockQualificationAndSubmission() throws Exception {
        String script = lua();

        int expireGuard = script.indexOf("if is_error(expireReply) or expireReply ~= 1 then");
        int compDel = script.indexOf("local compDel = redis.pcall('del', submissionKey)", expireGuard);
        assertThat(expireGuard).isNotNegative();
        assertThat(expireGuard).isLessThan(compDel);
        assertThat(script).contains("or not rollback_verify() then");
    }

    @Test
    void xaddFailureRollsBackStockQualificationAndSubmission() throws Exception {
        String script = lua();

        int xaddGuard = script.indexOf("if is_error(xaddReply) then");
        int compDel = script.indexOf("local compDel = redis.pcall('del', submissionKey)", xaddGuard);
        assertThat(xaddGuard).isNotNegative();
        assertThat(xaddGuard).isLessThan(compDel);
        assertThat(script).contains("or not rollback_verify() then");
    }

    @Test
    void incrbyReturnValueIsChecked() throws Exception {
        String script = lua();
        assertThat(script).contains("tonumber(incrReply) ~= stock - 1");
    }

    @Test
    void saddAndExpireReturnValuesAreChecked() throws Exception {
        String script = lua();
        assertThat(script).contains("or saddReply ~= 1");
        assertThat(script).contains("or expireReply ~= 1");
    }

    @Test
    void xaddFailsOnlyOnErrorReply() throws Exception {
        String script = lua();
        assertThat(script).contains("if is_error(xaddReply) then");
        assertThat(script).doesNotContain("type(xaddReply) ~= 'string'");
    }

    @Test
    void rollbackVerifiesStockMemberAndSubmissionPostconditions() throws Exception {
        String script = lua();
        int verify = script.indexOf("local function rollback_verify()");
        assertThat(verify).isGreaterThan(-1);
        assertThat(script).contains("afterStock == stockRaw and afterMember == 0 and submissionExists == 0");
        // HSET/EXPIRE/XADD 三个失败分支都必须调用统一回滚后置条件验证
        assertThat(countOccurrences(script, "or not rollback_verify() then")).isEqualTo(3);
    }

    @Test
    void writeOrderIsIncrSaddHsetExpireXadd() throws Exception {
        String script = lua();

        int incr = script.indexOf("redis.pcall('incrby', stockKey, -1)");
        int sadd = script.indexOf("redis.pcall('sadd', orderKey, userId)");
        int hset = script.indexOf("redis.pcall('hset'");
        int expire = script.indexOf("redis.pcall('expire', submissionKey, submissionTtlSeconds)");
        int xadd = script.indexOf("redis.pcall('xadd'");
        assertThat(incr).isNotNegative().isLessThan(sadd);
        assertThat(sadd).isLessThan(hset);
        assertThat(hset).isLessThan(expire);
        assertThat(expire).isLessThan(xadd);
    }

    @Test
    void existingSubmissionHashIsRejectedBeforeAnyBusinessWrite() throws Exception {
        String script = lua();

        assertThat(script).contains("redis.call('exists', submissionKey) == 1");
        assertThat(script).contains("return 6");
        int existsCheck = script.indexOf("redis.call('exists', submissionKey) == 1");
        int firstWrite = script.indexOf("redis.pcall('incrby'");
        int hset = script.indexOf("redis.pcall('hset'");
        assertThat(existsCheck).isNotNegative();
        assertThat(existsCheck).isLessThan(firstWrite);
        assertThat(existsCheck).isLessThan(hset);
    }

    @Test
    void existingSubmissionHashIsNeverOverwrittenByHset() throws Exception {
        String script = lua();

        int existsCheck = script.indexOf("redis.call('exists', submissionKey) == 1");
        int hset = script.indexOf("redis.pcall('hset'");
        assertThat(existsCheck).isNotNegative();
        assertThat(existsCheck).isLessThan(hset);
        assertThat(countOccurrences(script, "redis.pcall('hset'")).isEqualTo(1);
    }

    @Test
    void originalFiveArgOrderAndReturnCodesPreserved() throws Exception {
        String script = lua();

        assertThat(script).contains("local voucherId = ARGV[1]");
        assertThat(script).contains("local userId = ARGV[2]");
        assertThat(script).contains("local orderId = ARGV[3]");
        assertThat(script).contains("local currentTimeRaw = ARGV[4]");
        assertThat(script).contains("local submissionTtlRaw = ARGV[5]");
        for (String code : new String[]{"return 0", "return 1", "return 2", "return 3", "return 4", "return 5", "return 6"}) {
            assertThat(script).contains(code);
        }
    }

    private Integer countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }
}
