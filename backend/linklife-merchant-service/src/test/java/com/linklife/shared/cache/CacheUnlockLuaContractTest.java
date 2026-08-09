package com.linklife.shared.cache;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * cache-unlock.lua 静态契约测试：owner 匹配才删除，owner 不匹配返回 0。
 * 这是脚本契约测试，不是 Redis 集成测试。
 */
class CacheUnlockLuaContractTest {

    private String lua() throws Exception {
        return StreamUtils.copyToString(
                new ClassPathResource("cache-unlock.lua").getInputStream(), StandardCharsets.UTF_8);
    }

    @Test
    void scriptDeletesOnlyWhenOwnerMatches() throws Exception {
        String script = lua();

        assertThat(script).contains("redis.call('get', KEYS[1]) == ARGV[1]");
        assertThat(script).contains("return redis.call('del', KEYS[1])");
        assertThat(script.indexOf("redis.call('get', KEYS[1]) == ARGV[1]"))
                .isLessThan(script.indexOf("redis.call('del', KEYS[1])"));
    }

    @Test
    void mismatchedOwnerReturnsZero() throws Exception {
        String script = lua();

        assertThat(script).contains("return 0");
        assertThat(script.indexOf("return 0")).isGreaterThan(script.indexOf("redis.call('get', KEYS[1])"));
    }
}
