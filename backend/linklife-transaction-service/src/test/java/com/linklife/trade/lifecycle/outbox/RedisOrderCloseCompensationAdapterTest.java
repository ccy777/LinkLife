package com.linklife.trade.lifecycle.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisOrderCloseCompensationAdapter 单元测试：command 校验、KEYS/ARGV 契约、
 * 返回码映射、DataAccessException/null/未知码处理、不执行 SREM。
 */
class RedisOrderCloseCompensationAdapterTest {

    private RedisOrderCloseCompensationAdapter adapter;
    private StringRedisTemplate redisTemplate;

    private static final LocalDateTime HANDLED_AT = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

    @BeforeEach
    void setUp() {
        adapter = new RedisOrderCloseCompensationAdapter();
        redisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(adapter, "stringRedisTemplate", redisTemplate);
    }

    private OrderCloseCompensationCommand command() {
        return new OrderCloseCompensationCommand(
                1001L, 1L, 2L, "event-1", "VOUCHER_ORDER:CLOSED:1001:V1", 1, HANDLED_AT);
    }

    private void stubScript(Long result) {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(result);
    }

    @Test
    void invalidCommandIsRejected() {
        assertThatThrownBy(() -> new OrderCloseCompensationCommand(
                0L, 1L, 2L, "e", "VOUCHER_ORDER:CLOSED:0:V1", 1, HANDLED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCloseCompensationCommand(
                1001L, 1L, 2L, "e", "WRONG", 1, HANDLED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCloseCompensationCommand(
                1001L, 1L, 2L, "e", "VOUCHER_ORDER:CLOSED:1001:V1", 2, HANDLED_AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCloseCompensationCommand(
                1001L, 1L, 2L, "e", "VOUCHER_ORDER:CLOSED:1001:V1", 1,
                LocalDateTime.of(2026, 8, 6, 10, 0, 0, 123)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysAndArgsFollowFrozenContract() {
        stubScript(0L);

        adapter.compensate(command());

        verify(redisTemplate).execute(
                any(),
                eq(List.of("transaction:seckill:stock:2", "transaction:order:close:comp:1001")),
                eq("1001"), eq("1"), eq("2"), eq("event-1"),
                eq("VOUCHER_ORDER:CLOSED:1001:V1"),
                eq("2026-08-06T10:00:00"), eq("1"));
    }

    @Test
    void appliedAndAlreadyAppliedMapToSuccess() {
        stubScript(0L);
        assertThat(adapter.compensate(command()).outcome())
                .isEqualTo(OrderCloseCompensationResult.CompensationOutcome.SUCCESS);

        stubScript(1L);
        assertThat(adapter.compensate(command()).outcome())
                .isEqualTo(OrderCloseCompensationResult.CompensationOutcome.SUCCESS);
    }

    @Test
    void retryableScriptCodesMapToRetryable() {
        stubScript(20L);
        assertThat(adapter.compensate(command()))
                .isEqualTo(OrderCloseCompensationResult.retryable("REDIS_STOCK_INCREMENT_FAILED"));

        stubScript(21L);
        assertThat(adapter.compensate(command()))
                .isEqualTo(OrderCloseCompensationResult.retryable("REDIS_MARKER_WRITE_ROLLED_BACK"));
    }

    @Test
    void fatalScriptCodesMapToFatal() {
        assertFatal(10L, "REDIS_INVALID_ARGUMENT");
        assertFatal(11L, "REDIS_STOCK_KEY_MISSING");
        assertFatal(12L, "REDIS_STOCK_KEY_TYPE_INVALID");
        assertFatal(13L, "REDIS_STOCK_VALUE_INVALID");
        assertFatal(14L, "REDIS_MARKER_KEY_TYPE_INVALID");
        assertFatal(15L, "REDIS_MARKER_CORRUPT");
        assertFatal(16L, "REDIS_MARKER_IDENTITY_CONFLICT");
        assertFatal(22L, "REDIS_MARKER_WRITE_ROLLBACK_FAILED");
        assertFatal(99L, "REDIS_UNKNOWN_CODE");
    }

    @Test
    void largeOrNegativeLongCodesMapToUnknownFatal() {
        // 完整 Long 精确比较：不得因 int 窄化把未知码误判成 0/1 成功码
        assertFatal(4294967296L, "REDIS_UNKNOWN_CODE");
        assertFatal(4294967297L, "REDIS_UNKNOWN_CODE");
        assertFatal(Long.MAX_VALUE, "REDIS_UNKNOWN_CODE");
        assertFatal(-1L, "REDIS_UNKNOWN_CODE");
    }

    @Test
    void dataAccessExceptionMapsToRetryable() {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        assertThat(adapter.compensate(command()))
                .isEqualTo(OrderCloseCompensationResult.retryable("REDIS_COMPENSATION_ACCESS_FAILED"));
    }

    @Test
    void nullResultMapsToFatal() {
        stubScript(null);
        assertThat(adapter.compensate(command()))
                .isEqualTo(OrderCloseCompensationResult.fatal("REDIS_COMPENSATION_NULL_RESULT"));
    }

    @Test
    void returnCodeMappingNeverNarrowsToInt() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/lifecycle/outbox/RedisOrderCloseCompensationAdapter.java")),
                StandardCharsets.UTF_8);
        assertThat(source).doesNotContain("raw.intValue()");
        assertThat(source).contains("long code = raw.longValue();");
    }

    @Test
    void adapterDoesNotExecuteSrem() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/lifecycle/outbox/RedisOrderCloseCompensationAdapter.java")),
                StandardCharsets.UTF_8);
        assertThat(source)
                .doesNotContain("srem")
                .doesNotContain("opsForSet");
    }

    private void assertFatal(Long code, String expectedCode) {
        stubScript(code);
        assertThat(adapter.compensate(command()))
                .isEqualTo(OrderCloseCompensationResult.fatal(expectedCode));
    }

    static List<Integer> returnCodeMappingCases() {
        return List.of(0, 1, 10, 11, 12, 13, 14, 15, 16, 20, 21, 22);
    }
}
