package com.linklife.trade.admission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import static com.linklife.trade.admission.SeckillAdmissionDecision.ACCEPTED;
import static com.linklife.trade.admission.SeckillAdmissionDecision.DUPLICATE_ORDER;
import static com.linklife.trade.admission.SeckillAdmissionDecision.ENDED;
import static com.linklife.trade.admission.SeckillAdmissionDecision.NOT_INITIALIZED;
import static com.linklife.trade.admission.SeckillAdmissionDecision.NOT_STARTED;
import static com.linklife.trade.admission.SeckillAdmissionDecision.OUT_OF_STOCK;
import static com.linklife.trade.admission.SeckillAdmissionDecision.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RedisSeckillAdmissionAdapter 单元测试：验证 seckill.lua 0—6 返回码映射、
 * null/未知/大值 fail-closed、currentTime 参数透传以及适配器不依赖数据库/事务组件。
 */
class RedisSeckillAdmissionAdapterTest {

    private StringRedisTemplate redisTemplate;
    private RedisSeckillAdmissionAdapter adapter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        adapter = new RedisSeckillAdmissionAdapter();
        ReflectionTestUtils.setField(adapter, "stringRedisTemplate", redisTemplate);
    }

    private SeckillAdmissionDecision admit(Long luaResult) {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(luaResult);
        return adapter.admit(10L, 1L, 999L, 1000L, 86400L);
    }

    @Test
    void luaZeroMapsToAccepted() {
        assertThat(admit(0L)).isEqualTo(ACCEPTED);
    }

    @Test
    void luaOneMapsToOutOfStock() {
        assertThat(admit(1L)).isEqualTo(OUT_OF_STOCK);
    }

    @Test
    void luaTwoMapsToDuplicateOrder() {
        assertThat(admit(2L)).isEqualTo(DUPLICATE_ORDER);
    }

    @Test
    void luaThreeMapsToNotInitialized() {
        assertThat(admit(3L)).isEqualTo(NOT_INITIALIZED);
    }

    @Test
    void luaFourMapsToNotStarted() {
        assertThat(admit(4L)).isEqualTo(NOT_STARTED);
    }

    @Test
    void luaFiveMapsToEnded() {
        assertThat(admit(5L)).isEqualTo(ENDED);
    }

    @Test
    void luaSixMapsToUnavailable() {
        assertThat(admit(6L)).isEqualTo(UNAVAILABLE);
    }

    @Test
    void luaNullMapsToUnavailable() {
        assertThat(admit(null)).isEqualTo(UNAVAILABLE);
    }

    @Test
    void luaUnknownCodeMapsToUnavailable() {
        assertThat(admit(99L)).isEqualTo(UNAVAILABLE);
    }

    @Test
    void truncatedLargeCodeIsNotMappedToAccepted() {
        // 4294967296L.intValue() == 0，按完整 long 匹配时必须 fail-closed
        assertThat(admit(4294967296L)).isEqualTo(UNAVAILABLE);
    }

    @Test
    void outOfRangeLongTruncatingToKnownCodesIsUnavailable() {
        // 4294967297L..4294967301L 的 intValue() 分别为 1..5，但完整 long 不在契约内
        for (long i = 0; i < 5; i++) {
            assertThat(admit(4294967297L + i)).isEqualTo(UNAVAILABLE);
        }
    }

    @Test
    void nonSuccessNeverMapsToAccepted() {
        for (Long code : new Long[]{1L, 2L, 3L, 4L, 5L, 6L, null, 99L}) {
            assertThat(admit(code)).isNotEqualTo(ACCEPTED);
        }
    }

    @Test
    void luaFourthArgumentIsCurrentTimeMillis() {
        AtomicReference<Object[]> luaArgsRef = new AtomicReference<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Object[] raw = invocation.getArguments();
                    Object[] luaArgs = (raw.length == 3 && raw[2] instanceof Object[])
                            ? (Object[]) raw[2]
                            : Arrays.copyOfRange(raw, 2, raw.length);
                    luaArgsRef.set(luaArgs);
                    return 0L;
                });

        long currentTimeMillis = 123456789L;
        adapter.admit(10L, 1L, 999L, currentTimeMillis, 86400L);

        Object[] luaArgs = luaArgsRef.get();
        assertThat(luaArgs).hasSize(5);
        assertThat(luaArgs[0]).isEqualTo("10");
        assertThat(luaArgs[1]).isEqualTo("1");
        assertThat(luaArgs[2]).isEqualTo("999");
        assertThat(luaArgs[3]).isEqualTo(String.valueOf(currentTimeMillis));
        assertThat(luaArgs[4]).isEqualTo("86400");
    }

    @Test
    void luaFifthArgumentIsSubmissionTtlSeconds() {
        AtomicReference<Object[]> luaArgsRef = new AtomicReference<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Object[] raw = invocation.getArguments();
                    Object[] luaArgs = (raw.length == 3 && raw[2] instanceof Object[])
                            ? (Object[]) raw[2]
                            : Arrays.copyOfRange(raw, 2, raw.length);
                    luaArgsRef.set(luaArgs);
                    return 0L;
                });

        adapter.admit(10L, 1L, 999L, 1000L, 3600L);

        Object[] luaArgs = luaArgsRef.get();
        assertThat(luaArgs).hasSize(5);
        assertThat(luaArgs[4]).isEqualTo("3600");
    }

    @Test
    void adapterDoesNotDependOnDatabaseOrTransactionComponents() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/admission/RedisSeckillAdmissionAdapter.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("import com.linklife.trade.mapper.VoucherOrderMapper")
                .doesNotContain("import org.springframework.transaction.support.TransactionTemplate")
                .doesNotContain("import org.redisson.api.RedissonClient")
                .doesNotContain("import com.linklife.promotion.service.ISeckillVoucherService")
                .doesNotContain("import com.linklife.identity.security.UserHolder")
                .doesNotContain("import com.linklife.shared.redis.RedisIdWorker")
                .doesNotContain("import java.util.concurrent.ExecutorService")
                .doesNotContain("import org.springframework.data.redis.connection.stream.MapRecord")
                .doesNotContain("import com.linklife.shared.api.Result");
    }
}
