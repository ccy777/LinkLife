package com.linklife.trade.lifecycle.outbox;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * order-close-compensation.lua 静态契约测试：
 * 预检先于写操作、INCRBY +1 先于单次 HSET、HSET 失败显式 INCRBY -1 回滚并检查结果、
 * 无 SREM/SADD/XADD/EXPIRE/PEXPIRE、无 marker TTL、返回码与 Java 适配器一致。
 */
class OrderCloseCompensationLuaContractTest {

    @Test
    void scriptResourceIsDeclaredOnceAndExists() throws Exception {
        String adapterSource = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/lifecycle/outbox/RedisOrderCloseCompensationAdapter.java")),
                StandardCharsets.UTF_8);
        assertThat(adapterSource).contains("new ClassPathResource(\"order-close-compensation.lua\")");
        assertThat(Files.exists(Paths.get("src/main/resources/order-close-compensation.lua"))).isTrue();
        assertThat(RedisOrderCloseCompensationAdapter.COMPENSATION_SCRIPT).isNotNull();
    }

    @Test
    void usesStockAndMarkerKeys() throws Exception {
        String lua = readLua();

        assertThat(lua).contains("KEYS[1]").contains("KEYS[2]");
        assertThat(lua).contains("transaction:seckill:stock:").contains("transaction:order:close:comp:");
    }

    @Test
    void prechecksOccurBeforeAnyWrite() throws Exception {
        String lua = readLua();

        int firstIncr = lua.indexOf("redis.pcall('incrby', stockKey, 1)");
        int typeCheck = lua.indexOf("redis.call('type'");
        int stockGet = lua.indexOf("redis.call('get', stockKey)");
        int markerCheck = lua.indexOf("redis.call('hgetall', markerKey)");

        assertThat(firstIncr).isGreaterThan(-1);
        assertThat(typeCheck).isLessThan(firstIncr);
        assertThat(stockGet).isLessThan(firstIncr);
        assertThat(markerCheck).isLessThan(firstIncr);
    }

    @Test
    void incrementPrecedesSingleHsetOfCompleteMarker() throws Exception {
        String lua = readLua();

        int incr = lua.indexOf("redis.pcall('incrby', stockKey, 1)");
        int hset = lua.indexOf("redis.pcall('hset', markerKey,");
        assertThat(incr).isLessThan(hset);

        String hsetBlock = lua.substring(hset, Math.min(hset + 700, lua.length()));
        assertThat(hsetBlock)
                .contains("'state', 'done'")
                .contains("'eventId', eventId")
                .contains("'businessKey', businessKey")
                .contains("'orderId', orderId")
                .contains("'userId', userId")
                .contains("'voucherId', voucherId")
                .contains("'handledAt', handledAt")
                .contains("'eventVersion', eventVersion");
    }

    @Test
    void hsetFailureExplicitlyRollsBackIncrement() throws Exception {
        String lua = readLua();

        int hset = lua.indexOf("redis.pcall('hset', markerKey,");
        int rollback = lua.indexOf("redis.pcall('incrby', stockKey, -1)");
        int rollbackCheck = lua.indexOf("rollbackReply.err");

        assertThat(rollback).isGreaterThan(hset);
        assertThat(rollbackCheck).isGreaterThan(rollback);
        assertThat(lua).contains("return 21").contains("return 22");
    }

    @Test
    void forbiddenCommandsAndMarkerTtlAreAbsent() throws Exception {
        String lua = readLua();

        assertThat(lua)
                .doesNotContain("srem")
                .doesNotContain("sadd")
                .doesNotContain("xadd")
                .doesNotContain("expire")
                .doesNotContain("pexpire")
                .doesNotContain("EXPIRE")
                .doesNotContain("PEXPIRE");
    }

    @Test
    void markerIdempotencyAndConflictReturnBeforeWrite() throws Exception {
        String lua = readLua();

        int firstIncr = lua.indexOf("redis.pcall('incrby', stockKey, 1)");
        int alreadyApplied = lua.indexOf("return 1");
        int conflict = lua.indexOf("return 16");

        assertThat(alreadyApplied).isGreaterThan(-1).isLessThan(firstIncr);
        assertThat(conflict).isGreaterThan(-1).isLessThan(firstIncr);
    }

    @Test
    void keysAndArgsCountCheckedBeforeAnyWrite() throws Exception {
        String lua = readLua();

        int countCheck = lua.indexOf("#KEYS ~= 2 or #ARGV ~= 7");
        int firstIncr = lua.indexOf("redis.pcall('incrby', stockKey, 1)");

        assertThat(countCheck).isGreaterThan(-1).isLessThan(firstIncr);
    }

    @Test
    void canonicalPositiveIntegerPatternRejectsNonCanonicalIds() throws Exception {
        String lua = readLua();

        int pattern = lua.indexOf("'^[1-9]%d*$'");
        int firstIncr = lua.indexOf("redis.pcall('incrby', stockKey, 1)");
        assertThat(pattern).isGreaterThan(-1).isLessThan(firstIncr);

        // 禁止只依赖 tonumber(value) > 0 作为唯一格式校验
        assertThat(lua).contains("not is_positive_integer(orderId)");
        assertThat(lua).doesNotContain("orderIdNum == nil or orderIdNum <= 0");
    }

    @Test
    void eventVersionMustBeExactStringOne() throws Exception {
        String lua = readLua();

        int versionCheck = lua.indexOf("eventVersion ~= '1'");
        int firstIncr = lua.indexOf("redis.pcall('incrby', stockKey, 1)");

        assertThat(versionCheck).isGreaterThan(-1).isLessThan(firstIncr);
    }

    @Test
    void markerCompletenessIncludesHandledAt() throws Exception {
        String lua = readLua();

        // marker 已存在时必须读取 handledAt
        assertThat(lua).contains("elseif field == 'handledAt' then markerHandledAt = value");
        // handledAt 缺失或空字符串 → MARKER_CORRUPT（15）
        int handledAtCorruptCheck = lua.indexOf("or markerHandledAt == nil or markerHandledAt == ''");
        int corruptReturn = lua.indexOf("return 15");
        assertThat(handledAtCorruptCheck).isGreaterThan(-1).isLessThan(corruptReturn);
    }

    @Test
    void handledAtIsNotComparedInIdentityConflict() throws Exception {
        String lua = readLua();

        int conflictStart = lua.indexOf("-- 身份冲突只比较");
        int conflictReturn = lua.indexOf("return 16");
        assertThat(conflictStart).isGreaterThan(-1).isLessThan(conflictReturn);

        String conflictBlock = lua.substring(conflictStart, conflictReturn);
        assertThat(conflictBlock)
                .contains("markerEventId ~= eventId")
                .contains("markerBusinessKey ~= businessKey")
                .contains("markerOrderId ~= orderId")
                .contains("markerUserId ~= userId")
                .contains("markerVoucherId ~= voucherId")
                .contains("markerEventVersion ~= eventVersion")
                .doesNotContain("markerHandledAt ~= handledAt");
    }

    @Test
    void returnCodesMatchAdapterMapping() {
        assertThat(RedisOrderCloseCompensationAdapterTest.returnCodeMappingCases())
                .contains(0, 1, 10, 11, 12, 13, 14, 15, 16, 20, 21, 22);
    }

    @Test
    void existingSeckillLuaIsUnchanged() throws Exception {
        // 仅证明本任务没有修改既有 seckill.lua 的可疑内容；完整差异由静态审计覆盖。
        String seckill = new String(Files.readAllBytes(
                Paths.get("src/main/resources/seckill.lua")), StandardCharsets.UTF_8);
        assertThat(seckill).contains("sismember").contains("return 2");
    }

    private String readLua() throws Exception {
        return new String(Files.readAllBytes(
                Paths.get("src/main/resources/order-close-compensation.lua")), StandardCharsets.UTF_8);
    }
}
