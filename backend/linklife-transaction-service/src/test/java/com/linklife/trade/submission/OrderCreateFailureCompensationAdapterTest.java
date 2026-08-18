package com.linklife.trade.submission;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderCreateFailureCompensationAdapter 单元测试：command 校验、KEYS/ARGV 契约、
 * 返回码映射、DataAccessException/null/未知码处理、不直接执行 SREM/SADD。
 */
class OrderCreateFailureCompensationAdapterTest {

    private OrderCreateFailureCompensationAdapter adapter;
    private StringRedisTemplate redisTemplate;

    private static final LocalDateTime HANDLED_AT = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

    @BeforeEach
    void setUp() {
        adapter = new OrderCreateFailureCompensationAdapter();
        redisTemplate = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(adapter, "stringRedisTemplate", redisTemplate);
    }

    private OrderCreateFailureCompensationCommand releaseCommand() {
        return new OrderCreateFailureCompensationCommand(
                1001L, 1L, 2L, OrderCreateCompensationMode.RESTORE_STOCK_AND_RELEASE_QUALIFICATION,
                0L, HANDLED_AT, 1);
    }

    private OrderCreateFailureCompensationCommand keepCommand() {
        return new OrderCreateFailureCompensationCommand(
                1001L, 1L, 2L, OrderCreateCompensationMode.RESTORE_STOCK_KEEP_QUALIFICATION,
                9999L, HANDLED_AT, 1);
    }

    private void stubScript(Long result) {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class))).thenReturn(result);
    }

    @Test
    void invalidCommandsAreRejected() {
        assertThatThrownBy(() -> new OrderCreateFailureCompensationCommand(
                0L, 1L, 2L, OrderCreateCompensationMode.RESTORE_STOCK_AND_RELEASE_QUALIFICATION,
                0L, HANDLED_AT, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCreateFailureCompensationCommand(
                1001L, 1L, 2L, OrderCreateCompensationMode.RESTORE_STOCK_AND_RELEASE_QUALIFICATION,
                9999L, HANDLED_AT, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCreateFailureCompensationCommand(
                1001L, 1L, 2L, OrderCreateCompensationMode.RESTORE_STOCK_KEEP_QUALIFICATION,
                0L, HANDLED_AT, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCreateFailureCompensationCommand(
                1001L, 1L, 2L, OrderCreateCompensationMode.RESTORE_STOCK_KEEP_QUALIFICATION,
                9999L, LocalDateTime.of(2026, 8, 6, 10, 0, 0, 123), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCreateFailureCompensationCommand(
                1001L, 1L, 2L, OrderCreateCompensationMode.RESTORE_STOCK_KEEP_QUALIFICATION,
                9999L, HANDLED_AT, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysAndArgsFollowFrozenContract() {
        stubScript(0L);

        adapter.compensate(releaseCommand());

        verify(redisTemplate).execute(
                any(),
                eq(List.of("transaction:seckill:stock:2", "transaction:seckill:order:2", "transaction:order:create:comp:1001")),
                eq("1001"), eq("1"), eq("2"),
                eq("RESTORE_STOCK_AND_RELEASE_QUALIFICATION"),
                eq("0"), eq("2026-08-06T10:00:00"), eq("1"));
    }

    @Test
    void keepModePassesExistingOrderId() {
        stubScript(0L);

        adapter.compensate(keepCommand());

        verify(redisTemplate).execute(
                any(), anyList(),
                eq("1001"), eq("1"), eq("2"),
                eq("RESTORE_STOCK_KEEP_QUALIFICATION"),
                eq("9999"), eq("2026-08-06T10:00:00"), eq("1"));
    }

    @Test
    void appliedAndAlreadyAppliedMapToSuccess() {
        stubScript(0L);
        assertThat(adapter.compensate(releaseCommand()).outcome())
                .isEqualTo(OrderCreateFailureCompensationResult.CompensationOutcome.SUCCESS);

        stubScript(1L);
        assertThat(adapter.compensate(releaseCommand()).outcome())
                .isEqualTo(OrderCreateFailureCompensationResult.CompensationOutcome.SUCCESS);
    }

    @Test
    void retryableScriptCodesMapToRetryable() {
        assertRetryable(20L, "CREATE_COMP_STOCK_INCREMENT_FAILED");
        assertRetryable(21L, "CREATE_COMP_QUALIFICATION_REMOVE_ROLLED_BACK");
        assertRetryable(23L, "CREATE_COMP_MARKER_WRITE_ROLLED_BACK");
    }

    @Test
    void fatalScriptCodesMapToFatal() {
        assertFatal(10L, "CREATE_COMP_INVALID_ARGUMENT");
        assertFatal(11L, "CREATE_COMP_STOCK_KEY_MISSING");
        assertFatal(12L, "CREATE_COMP_STOCK_KEY_TYPE_INVALID");
        assertFatal(13L, "CREATE_COMP_STOCK_VALUE_INVALID");
        assertFatal(14L, "CREATE_COMP_QUALIFICATION_KEY_TYPE_INVALID");
        assertFatal(15L, "CREATE_COMP_MARKER_KEY_TYPE_INVALID");
        assertFatal(16L, "CREATE_COMP_MARKER_CORRUPT");
        assertFatal(17L, "CREATE_COMP_MARKER_IDENTITY_CONFLICT");
        assertFatal(22L, "CREATE_COMP_QUALIFICATION_REMOVE_ROLLBACK_FAILED");
        assertFatal(24L, "CREATE_COMP_MARKER_WRITE_ROLLBACK_FAILED");
        assertFatal(99L, "CREATE_COMP_UNKNOWN_CODE");
    }

    @Test
    void dataAccessExceptionMapsToRetryable() {
        when(redisTemplate.execute(any(), anyList(), any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("redis down"));

        assertThat(adapter.compensate(releaseCommand()))
                .isEqualTo(OrderCreateFailureCompensationResult.retryable("CREATE_COMP_ACCESS_FAILED"));
    }

    @Test
    void nullResultMapsToFatal() {
        stubScript(null);
        assertThat(adapter.compensate(releaseCommand()))
                .isEqualTo(OrderCreateFailureCompensationResult.fatal("CREATE_COMP_NULL_RESULT"));
    }

    @Test
    void adapterDoesNotExecuteSetCommandsDirectly() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/submission/OrderCreateFailureCompensationAdapter.java")),
                StandardCharsets.UTF_8);
        assertThat(source).doesNotContain("opsForSet()").doesNotContain("srem").doesNotContain("sadd");
    }

    static List<Integer> returnCodeMappingCases() {
        return List.of(0, 1, 10, 11, 12, 13, 14, 15, 16, 17, 20, 21, 22, 23, 24);
    }

    private void assertRetryable(Long code, String expectedCode) {
        stubScript(code);
        assertThat(adapter.compensate(releaseCommand()))
                .isEqualTo(OrderCreateFailureCompensationResult.retryable(expectedCode));
    }

    private void assertFatal(Long code, String expectedCode) {
        stubScript(code);
        assertThat(adapter.compensate(releaseCommand()))
                .isEqualTo(OrderCreateFailureCompensationResult.fatal(expectedCode));
    }
}
