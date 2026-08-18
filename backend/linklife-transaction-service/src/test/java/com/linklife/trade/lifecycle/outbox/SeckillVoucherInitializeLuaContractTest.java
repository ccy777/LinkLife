package com.linklife.trade.lifecycle.outbox;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * seckill-voucher-initialize.lua 静态契约测试：
 * KEYS/ARGV 数量、写前校验、marker 完整字段、写序 SET→SET→SET→HSET、
 * 失败显式回滚并验证四个 Key 均不存在、实时库存不重置、无 Set/Stream/TTL。
 */
class SeckillVoucherInitializeLuaContractTest {

    private String lua() throws Exception {
        return new String(Files.readAllBytes(
                Paths.get("src/main/resources/seckill-voucher-initialize.lua")),
                StandardCharsets.UTF_8);
    }

    @Test
    void keysAndArgsCountCheckedBeforeAnyWrite() throws Exception {
        String script = lua();
        int countCheck = script.indexOf("#KEYS ~= 4 or #ARGV ~= 8");
        int firstWrite = script.indexOf("redis.pcall('set', stockKey");
        assertThat(countCheck).isGreaterThan(-1).isLessThan(firstWrite);
    }

    @Test
    void preWriteValidationsEnforced() throws Exception {
        String script = lua();
        assertThat(script).contains("tonumber(initialStock) > 2147483647");
        assertThat(script).contains("tonumber(beginEpochMillis) >= tonumber(endEpochMillis)");
        assertThat(script).contains("eventVersion ~= '1'");
        assertThat(script).contains("is_non_negative_integer(initialStock)");
    }

    @Test
    void markerContainsAllFrozenFields() throws Exception {
        String script = lua();
        String hsetBlock = script.substring(script.indexOf("redis.pcall('hset', markerKey,"));
        assertThat(hsetBlock)
                .contains("'state', 'done'")
                .contains("'voucherId', voucherId")
                .contains("'initialStock', initialStock")
                .contains("'beginEpochMillis', beginEpochMillis")
                .contains("'endEpochMillis', endEpochMillis")
                .contains("'eventId', eventId")
                .contains("'businessKey', businessKey")
                .contains("'handledAt', handledAt")
                .contains("'eventVersion', eventVersion");
        assertThat(script).contains("elseif field == 'handledAt' then mHandledAt = value");
    }

    @Test
    void writeOrderIsSetStockSetBeginSetEndThenHset() throws Exception {
        String script = lua();
        int setStock = script.indexOf("redis.pcall('set', stockKey, initialStock)");
        int setBegin = script.indexOf("redis.pcall('set', beginKey, beginEpochMillis)");
        int setEnd = script.indexOf("redis.pcall('set', endKey, endEpochMillis)");
        int hset = script.indexOf("redis.pcall('hset', markerKey,");
        assertThat(setStock).isGreaterThan(-1).isLessThan(setBegin);
        assertThat(setBegin).isLessThan(setEnd);
        assertThat(setEnd).isLessThan(hset);
    }

    @Test
    void writeFailureRollsBackCreatedKeysAndVerifiesAllAbsent() throws Exception {
        String script = lua();
        assertThat(script).contains("local function rollback_created()");
        assertThat(script).contains("local function verify_all_absent()");
        assertThat(script).contains("return 20").contains("return 21");
        assertThat(script).contains("redis.call('exists', markerKey) == 0");
    }

    @Test
    void preexistingBusinessKeyWithoutMarkerIsConflict() throws Exception {
        String script = lua();
        assertThat(script).contains("stockType ~= 'none' or beginType ~= 'none' or endType ~= 'none'");
        assertThat(script).contains("return 18");
    }

    @Test
    void initializedStateDoesNotRequireStockEqualToInitialStock() throws Exception {
        String script = lua();
        assertThat(script).contains("不要求等于 initialStock");
        assertThat(script).doesNotContain("stockRaw ~= initialStock");
        assertThat(script).doesNotContain("stockRaw ~= mInitialStock");
    }

    @Test
    void noSetStreamOrTtlOperations() throws Exception {
        String script = lua();
        assertThat(script)
                .doesNotContain("sismember")
                .doesNotContain("sadd")
                .doesNotContain("srem")
                .doesNotContain("xadd")
                .doesNotContain("expire")
                .doesNotContain("pexpire");
    }

    @Test
    void returnCodesMatchAdapterMapping() {
        assertThat(SeckillVoucherInitializeAdapterTest.returnCodeMappingCases())
                .contains(0, 1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 21);
    }
}
