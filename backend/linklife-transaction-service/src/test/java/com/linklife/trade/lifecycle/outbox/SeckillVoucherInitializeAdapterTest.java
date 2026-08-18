package com.linklife.trade.lifecycle.outbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SeckillVoucherInitializeAdapter 单元测试：command 校验、KEYS/ARGV 契约、
 * 返回码映射、DataAccessException/null/未知码处理、无 Long 窄化。
 */
class SeckillVoucherInitializeAdapterTest {

    private SeckillVoucherInitializeAdapter adapter;
    private StringRedisTemplate redisTemplate;

    private static final LocalDateTime HANDLED_AT = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

    @BeforeEach
    void setUp() {
        adapter = new SeckillVoucherInitializeAdapter();
        redisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(adapter, "stringRedisTemplate", redisTemplate);
    }

    private SeckillVoucherInitializeCommand command() {
        return new SeckillVoucherInitializeCommand(
                100L, 10, 1750000000000L, 1751000000000L,
                "event-1", "SECKILL_VOUCHER:CREATED:100:V1", HANDLED_AT, 1);
    }

    private void stubScript(Long result) {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(result);
    }

    @Test
    void invalidCommandsAreRejected() {
        assertThatThrownBy(() -> new SeckillVoucherInitializeCommand(
                0L, 10, 1L, 2L, "e", "k", HANDLED_AT, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeckillVoucherInitializeCommand(
                100L, -1, 1L, 2L, "e", "k", HANDLED_AT, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeckillVoucherInitializeCommand(
                100L, 10, 2L, 1L, "e", "k", HANDLED_AT, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeckillVoucherInitializeCommand(
                100L, 10, 1L, 2L, "e", "k",
                LocalDateTime.of(2026, 8, 6, 10, 0, 0, 123), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SeckillVoucherInitializeCommand(
                100L, 10, 1L, 2L, "e", "k", HANDLED_AT, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysAndArgsFollowFrozenContract() {
        stubScript(0L);

        adapter.initialize(command());

        verify(redisTemplate).execute(
                any(),
                eq(List.of("transaction:seckill:stock:100", "transaction:seckill:begin:100",
                        "transaction:seckill:end:100", "transaction:seckill:init:marker:100")),
                eq("100"), eq("10"), eq("1750000000000"), eq("1751000000000"),
                eq("event-1"), eq("SECKILL_VOUCHER:CREATED:100:V1"),
                eq("2026-08-06T10:00:00"), eq("1"));
    }

    @Test
    void initializedAndAlreadyInitializedMapToSuccess() {
        stubScript(0L);
        assertThat(adapter.initialize(command()).outcome())
                .isEqualTo(SeckillVoucherInitializeResult.InitializeOutcome.SUCCESS);
        stubScript(1L);
        assertThat(adapter.initialize(command()).outcome())
                .isEqualTo(SeckillVoucherInitializeResult.InitializeOutcome.SUCCESS);
    }

    @Test
    void safeRollbackCodeMapsToRetryable() {
        stubScript(20L);
        assertThat(adapter.initialize(command()))
                .isEqualTo(SeckillVoucherInitializeResult.retryable("SECKILL_INIT_WRITE_ROLLED_BACK"));
    }

    @Test
    void fatalCodesMapToFatal() {
        assertFatal(10L, "SECKILL_INIT_INVALID_ARGUMENT");
        assertFatal(11L, "SECKILL_INIT_STOCK_KEY_TYPE_INVALID");
        assertFatal(12L, "SECKILL_INIT_BEGIN_KEY_TYPE_INVALID");
        assertFatal(13L, "SECKILL_INIT_END_KEY_TYPE_INVALID");
        assertFatal(14L, "SECKILL_INIT_MARKER_KEY_TYPE_INVALID");
        assertFatal(15L, "SECKILL_INIT_MARKER_CORRUPT");
        assertFatal(16L, "SECKILL_INIT_MARKER_IDENTITY_CONFLICT");
        assertFatal(17L, "SECKILL_INIT_STATE_CORRUPT");
        assertFatal(18L, "SECKILL_INIT_PREEXISTING_STATE_CONFLICT");
        assertFatal(21L, "SECKILL_INIT_WRITE_ROLLBACK_FAILED");
        assertFatal(99L, "SECKILL_INIT_UNKNOWN_CODE");
    }

    @Test
    void dataAccessExceptionMapsToRetryable() {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("redis down"));
        assertThat(adapter.initialize(command()))
                .isEqualTo(SeckillVoucherInitializeResult.retryable("SECKILL_INIT_ACCESS_FAILED"));
    }

    @Test
    void nullResultMapsToFatal() {
        stubScript(null);
        assertThat(adapter.initialize(command()))
                .isEqualTo(SeckillVoucherInitializeResult.fatal("SECKILL_INIT_NULL_RESULT"));
    }

    static List<Integer> returnCodeMappingCases() {
        return List.of(0, 1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 20, 21);
    }

    private void assertFatal(Long code, String expectedCode) {
        stubScript(code);
        assertThat(adapter.initialize(command()))
                .isEqualTo(SeckillVoucherInitializeResult.fatal(expectedCode));
    }
}
