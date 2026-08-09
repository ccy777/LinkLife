package com.linklife.trade.submission;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * order-submission-transition.lua 直接契约测试：直接读取生产 Lua 文本，约束状态矩阵、
 * 身份检查、PERSISTED 不回退、损坏字段校验与 TTL，不 mock、不复制 Java 状态机。
 */
class OrderSubmissionTransitionLuaContractTest {

    private String lua() throws Exception {
        return StreamUtils.copyToString(
                new ClassPathResource("order-submission-transition.lua").getInputStream(),
                StandardCharsets.UTF_8);
    }

    @Test
    void argvOneToSevenOrderAndMeaning() throws Exception {
        String script = lua();

        assertThat(script).contains("local orderId = ARGV[1]");
        assertThat(script).contains("local userId = ARGV[2]");
        assertThat(script).contains("local voucherId = ARGV[3]");
        assertThat(script).contains("local targetState = ARGV[4]");
        assertThat(script).contains("local message = ARGV[5]");
        assertThat(script).contains("local updatedAt = ARGV[6]");
        assertThat(script).contains("local ttlSeconds = tonumber(ARGV[7])");
    }

    @Test
    void targetStateOnlyAllowsProcessingPersistedFailed() throws Exception {
        String script = lua();

        assertThat(script).contains(
                "targetState ~= 'PROCESSING' and targetState ~= 'PERSISTED' and targetState ~= 'FAILED'");
        assertThat(script).contains("return 1");
        assertThat(script).doesNotContain("targetState == 'UNKNOWN'");
    }

    @Test
    void existingStateWhitelistIsStrict() throws Exception {
        String script = lua();

        assertThat(script).contains(
                "currentState ~= 'ACCEPTED' and currentState ~= 'PROCESSING'");
        assertThat(script).contains(
                "currentState ~= 'PERSISTED' and currentState ~= 'FAILED'");
        assertThat(script).doesNotContain("currentState == 'UNKNOWN'");
    }

    @Test
    void unknownOrUnrecognizedStateReturnsCorruptionCodeThreeBeforeHset() throws Exception {
        String script = lua();

        int whitelist = script.indexOf("currentState ~= 'ACCEPTED'");
        int returnThree = script.indexOf("return 3");
        int hset = script.indexOf("redis.call('hset'");
        assertThat(whitelist).isNotNegative();
        assertThat(returnThree).isNotNegative();
        assertThat(whitelist).isLessThan(hset);
        assertThat(returnThree).isLessThan(hset);
    }

    @Test
    void identityCheckHappensBeforeHset() throws Exception {
        String script = lua();

        assertThat(script).contains("currentUserId ~= userId or currentVoucherId ~= voucherId");
        assertThat(script).contains("return 2");
        int identityCheck = script.indexOf("currentUserId ~= userId");
        int hset = script.indexOf("redis.call('hset'");
        assertThat(identityCheck).isNotNegative();
        assertThat(identityCheck).isLessThan(hset);
    }

    @Test
    void missingFieldsAndInvalidUpdatedAtReturnThreeBeforeHset() throws Exception {
        String script = lua();

        assertThat(script).contains("currentUpdatedAt");
        assertThat(script).contains("tonumber(currentUpdatedAt)");
        assertThat(script).contains("currentMessage");
        int updatedAtCheck = script.indexOf("tonumber(currentUpdatedAt)");
        int lastReturnThree = script.lastIndexOf("return 3");
        int hset = script.indexOf("redis.call('hset'");
        assertThat(updatedAtCheck).isNotNegative();
        assertThat(updatedAtCheck).isLessThan(hset);
        assertThat(lastReturnThree).isLessThan(hset);
    }

    @Test
    void failedToProcessingRecoveryBranchExists() throws Exception {
        String script = lua();

        assertThat(script).contains("currentState == 'FAILED'");
        assertThat(script).contains("finalState = 'PROCESSING'");
    }

    @Test
    void persistedStaysPersistedForProcessingRequest() throws Exception {
        String script = lua();

        assertThat(script).contains("currentState == 'PERSISTED'");
        assertThat(script).contains("finalState = 'PERSISTED'");
    }

    @Test
    void persistedStaysPersistedForFailedRequest() throws Exception {
        String script = lua();

        assertThat(script).contains("targetState == 'FAILED'");
        assertThat(script).contains("currentState == 'PERSISTED'");
        int persistedGuard = script.indexOf("currentState == 'PERSISTED'");
        int hset = script.indexOf("redis.call('hset'");
        assertThat(persistedGuard).isNotNegative();
        assertThat(persistedGuard).isLessThan(hset);
    }

    @Test
    void persistedMessageIsPreserved() throws Exception {
        String script = lua();

        assertThat(script).contains("finalMessage = currentMessage");
    }

    @Test
    void missingRecordAllowsCreateOfTargetState() throws Exception {
        String script = lua();

        assertThat(script).contains("local exists = redis.call('exists', submissionKey)");
        assertThat(script).contains("if exists == 1 then");
        assertThat(script).contains("finalState = 'PROCESSING'");
        assertThat(script).contains("finalState = 'PERSISTED'");
        assertThat(script).contains("finalState = 'FAILED'");
    }

    @Test
    void expireRunsAfterHset() throws Exception {
        String script = lua();

        int hset = script.indexOf("redis.call('hset'");
        int expire = script.indexOf("redis.call('expire'");
        assertThat(hset).isNotNegative();
        assertThat(expire).isGreaterThan(hset);
        assertThat(script).contains("redis.call('expire', submissionKey, ttlSeconds)");
    }

    @Test
    void successReturnsZero() throws Exception {
        assertThat(lua()).contains("return 0");
    }

    @Test
    void scriptNeverWritesUnknownState() throws Exception {
        String script = lua();

        assertThat(script).doesNotContain("'state', 'UNKNOWN'");
        assertThat(script).doesNotContain("finalState = 'UNKNOWN'");
        assertThat(script).doesNotContain("currentState == 'UNKNOWN'");
    }
}
