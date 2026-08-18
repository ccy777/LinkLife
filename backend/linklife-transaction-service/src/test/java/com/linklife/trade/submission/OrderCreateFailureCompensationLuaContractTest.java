package com.linklife.trade.submission;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * order-create-failure-compensation.lua 静态契约测试：
 * KEYS/ARGV 数量、mode 白名单与一致性、marker 完整字段、写序 INCRBY→SREM→HSET、
 * SREM 失败与 marker 写失败显式回滚并检查、无 SREM 于 KEEP 模式、无 TTL。
 */
class OrderCreateFailureCompensationLuaContractTest {

    private String lua() throws Exception {
        return new String(Files.readAllBytes(
                Paths.get("src/main/resources/order-create-failure-compensation.lua")),
                StandardCharsets.UTF_8);
    }

    @Test
    void keysAndArgsCountCheckedBeforeAnyWrite() throws Exception {
        String script = lua();
        int countCheck = script.indexOf("#KEYS ~= 3 or #ARGV ~= 7");
        int firstWrite = script.indexOf("redis.pcall('incrby', stockKey, 1)");
        assertThat(countCheck).isGreaterThan(-1).isLessThan(firstWrite);
    }

    @Test
    void modeWhitelistAndExistingOrderIdConsistencyEnforced() throws Exception {
        String script = lua();
        assertThat(script).contains("mode ~= 'RESTORE_STOCK_AND_RELEASE_QUALIFICATION'");
        assertThat(script).contains("mode ~= 'RESTORE_STOCK_KEEP_QUALIFICATION'");
        assertThat(script).contains("if existingOrderId ~= '0' then");
        assertThat(script).contains("if not is_positive_integer(existingOrderId) then");
    }

    @Test
    void markerContainsAllFrozenFieldsAndIdentityCheck() throws Exception {
        String script = lua();
        String hsetBlock = script.substring(script.indexOf("redis.pcall('hset', markerKey,"));
        assertThat(hsetBlock)
                .contains("'state', 'done'")
                .contains("'orderId', orderId")
                .contains("'userId', userId")
                .contains("'voucherId', voucherId")
                .contains("'mode', mode")
                .contains("'existingOrderId', existingOrderId")
                .contains("'handledAt', handledAt")
                .contains("'version', version");
        assertThat(script).contains("elseif field == 'handledAt' then markerHandledAt = value");
        assertThat(script).contains("or markerHandledAt == nil or markerHandledAt == ''");
    }

    @Test
    void releaseModePerformsSremBeforeMarkerWrite() throws Exception {
        String script = lua();
        int incr = script.indexOf("local incrReply = redis.pcall('incrby', stockKey, 1)");
        int srem = script.indexOf("redis.pcall('srem', qualificationKey, userId)", incr);
        int hset = script.indexOf("redis.pcall('hset', markerKey,");
        assertThat(incr).isGreaterThan(-1);
        assertThat(incr).isLessThan(srem);
        assertThat(srem).isGreaterThan(-1).isLessThan(hset);
    }

    @Test
    void sremFailureExplicitlyRollsBackIncrement() throws Exception {
        String script = lua();
        int sremGuard = script.indexOf("if is_error(sremReply) or (sremReply ~= 0 and sremReply ~= 1) then");
        int rollback = script.indexOf("redis.pcall('incrby', stockKey, -1)", sremGuard);
        assertThat(sremGuard).isGreaterThan(-1).isLessThan(rollback);
        assertThat(script).contains("return 21").contains("return 22");
    }

    @Test
    void markerWriteFailureRollsBackIncrementAndRestoresQualification() throws Exception {
        String script = lua();
        int hsetGuard = script.indexOf("if is_error(hsetReply) then");
        int rollbackIncr = script.indexOf("redis.pcall('incrby', stockKey, -1)", hsetGuard);
        int rollbackSadd = script.indexOf("redis.pcall('sadd', qualificationKey, userId)", hsetGuard);
        assertThat(hsetGuard).isGreaterThan(-1).isLessThan(rollbackIncr);
        assertThat(rollbackIncr).isLessThan(rollbackSadd);
        assertThat(script).contains("return 23").contains("return 24");
    }

    @Test
    void qualificationWasMemberIsReadBeforeAnyWrite() throws Exception {
        String script = lua();
        int memberRead = script.indexOf("redis.call('sismember', qualificationKey, userId)");
        int firstWrite = script.indexOf("redis.pcall('incrby', stockKey, 1)");
        int failClosed = script.indexOf("qualificationWasMember ~= 0 and qualificationWasMember ~= 1");
        assertThat(memberRead).isGreaterThan(-1).isLessThan(firstWrite);
        assertThat(failClosed).isGreaterThan(-1).isLessThan(firstWrite);
    }

    @Test
    void removedQualificationIsDeterminedBySremResult() throws Exception {
        String script = lua();
        assertThat(script).contains("removedQualification = (sremReply == 1)");
        assertThat(script).doesNotContain("removedQualification = true");
        assertThat(script).contains("sremReply ~= 0 and sremReply ~= 1");
    }

    @Test
    void sremZeroDoesNotRestoreQualificationOnMarkerFailure() throws Exception {
        String script = lua();
        int hsetGuard = script.indexOf("if is_error(hsetReply) then");
        int saddGuard = script.indexOf("if qualificationWasMember == 1 and removedQualification then", hsetGuard);
        int sadd = script.indexOf("redis.pcall('sadd', qualificationKey, userId)", hsetGuard);
        assertThat(hsetGuard).isGreaterThan(-1);
        assertThat(saddGuard).isGreaterThan(hsetGuard).isLessThan(sadd);
    }

    @Test
    void rollbackVerifiesStockAndMembershipRestored() throws Exception {
        String script = lua();
        int verify = script.indexOf("local function verify_restored()");
        assertThat(verify).isGreaterThan(-1);
        int hsetGuard = script.indexOf("if is_error(hsetReply) then");
        assertThat(verify).isLessThan(hsetGuard);
        assertThat(script).contains("afterStock == stockRaw and afterMember == qualificationWasMember");
        assertThat(script).contains("or not verify_restored() then");
    }

    @Test
    void sremFailureRestoresQualificationToPreWriteMembership() throws Exception {
        String script = lua();
        int sremGuard = script.indexOf("if is_error(sremReply) or (sremReply ~= 0 and sremReply ~= 1) then");
        int restore = script.indexOf("local restored = restore_qualification()", sremGuard);
        assertThat(sremGuard).isGreaterThan(-1).isLessThan(restore);
        assertThat(script).contains("restore_qualification");
    }

    @Test
    void keepModeNeverExecutesSrem() throws Exception {
        String script = lua();
        // SREM 只出现在两处：(1) 回滚辅助 restore_qualification（仅在回滚路径调用）；
        // (2) 释放资格写路径（被 mode 守卫包裹）。KEEP 模式运行时绝不执行 SREM/SADD。
        int incr = script.indexOf("local incrReply = redis.pcall('incrby', stockKey, 1)");
        int writeSrem = script.indexOf("redis.pcall('srem', qualificationKey, userId)", incr);
        int modeCheck = script.indexOf("if mode == 'RESTORE_STOCK_AND_RELEASE_QUALIFICATION' then");
        assertThat(writeSrem).isGreaterThan(-1);
        assertThat(modeCheck).isLessThan(writeSrem);
        assertThat(countOccurrences(script, "redis.pcall('srem'")).isEqualTo(2);
    }

    @Test
    void streamAndTtlCommandsAbsent() throws Exception {
        String script = lua();
        assertThat(script)
                .doesNotContain("xadd")
                .doesNotContain("expire")
                .doesNotContain("pexpire")
                .doesNotContain("del marker");
    }

    @Test
    void returnCodesMatchAdapterMapping() {
        assertThat(OrderCreateFailureCompensationAdapterTest.returnCodeMappingCases())
                .contains(0, 1, 10, 11, 12, 13, 14, 15, 16, 17, 20, 21, 22, 23, 24);
    }

    private int countOccurrences(String text, String needle) {
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
