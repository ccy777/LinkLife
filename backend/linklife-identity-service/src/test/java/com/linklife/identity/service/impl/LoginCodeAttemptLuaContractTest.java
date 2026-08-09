package com.linklife.identity.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * login-code-attempt.lua 静态脚本契约测试：校验 PTTL → INCR → PEXPIRE 顺序与返回码结构。
 * 这是脚本契约测试，不是 Redis 集成测试，本任务未在真实 Redis 中执行 Lua。
 */
class LoginCodeAttemptLuaContractTest {

    private String lua() throws Exception {
        return StreamUtils.copyToString(
                new ClassPathResource("login-code-attempt.lua").getInputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void scriptVerifiesPttlIncrPexpireOrder() throws Exception {
        String script = lua();

        int pttl = script.indexOf("redis.call('pttl'");
        int incr = script.indexOf("redis.call('incr'");
        int pexpire = script.indexOf("redis.call('pexpire'");

        assertThat(pttl).isNotNegative();
        assertThat(incr).isNotNegative();
        assertThat(pexpire).isNotNegative();
        assertThat(pttl).isLessThan(incr);
        assertThat(incr).isLessThan(pexpire);
    }

    @Test
    void scriptRejectsInvalidAttemptBeforeIncr() throws Exception {
        String script = lua();

        assertThat(script).contains("attemptRaw == '0' or string.match(attemptRaw, '^[1-9]%d*$') ~= nil");
        assertThat(script).contains("return -2");
        assertThat(script.indexOf("return -2")).isLessThan(script.indexOf("redis.call('incr'"));
    }

    @Test
    void scriptDeletesAttemptAndReturnsMinusThreeOnPexpireFailure() throws Exception {
        String script = lua();

        assertThat(script).contains("local ttlSet = redis.call('pexpire', attemptKey, remainingTtl)");
        assertThat(script).contains("if ttlSet ~= 1 then");
        assertThat(script).contains("redis.call('del', attemptKey)");
        assertThat(script).contains("return -3");
        assertThat(script.indexOf("redis.call('pexpire'"))
                .isLessThan(script.indexOf("redis.call('del', attemptKey)"));
        assertThat(script.indexOf("redis.call('del', attemptKey)"))
                .isLessThan(script.indexOf("return -3"));
    }
}
