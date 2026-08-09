package com.linklife.trade.submission;

import com.linklife.trade.entity.VoucherOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RedisOrderSubmissionStatusRepository 单元测试：状态转换 Lua 参数、fail-closed 返回码、
 * TTL 刷新、身份冲突、find 解析与畸形字段拒绝。不依赖真实 Redis/MySQL。
 */
class RedisOrderSubmissionStatusRepositoryTest {

    private static final long TTL_SECONDS = 86400L;

    private StringRedisTemplate redisTemplate;
    private HashOperations<String, Object, Object> hashOps;
    private RedisOrderSubmissionStatusRepository repository;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        hashOps = mock(HashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        repository = new RedisOrderSubmissionStatusRepository();
        ReflectionTestUtils.setField(repository, "stringRedisTemplate", redisTemplate);
    }

    private VoucherOrder order(long id, long userId, long voucherId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(id);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        return order;
    }

    private Object[] extractLuaArgs(InvocationOnMock invocation) {
        Object[] raw = invocation.getArguments();
        return (raw.length == 3 && raw[2] instanceof Object[])
                ? (Object[]) raw[2]
                : Arrays.copyOfRange(raw, 2, raw.length);
    }

    private Object[] captureTransitionArgs(Long luaResult) {
        AtomicReference<Object[]> argsRef = new AtomicReference<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    argsRef.set(extractLuaArgs(invocation));
                    return luaResult;
                });
        return argsRef.get();
    }

    @Test
    void markProcessingWritesProcessingStateWithIdentityAndTtl() {
        AtomicReference<Object[]> argsRef = new AtomicReference<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    argsRef.set(extractLuaArgs(invocation));
                    return 0L;
                });

        repository.markProcessing(order(1001L, 1L, 2L));

        Object[] args = argsRef.get();
        assertThat(args).hasSize(7);
        assertThat(args[0]).isEqualTo("1001");
        assertThat(args[1]).isEqualTo("1");
        assertThat(args[2]).isEqualTo("2");
        assertThat(args[3]).isEqualTo("PROCESSING");
        assertThat(args[4]).isEqualTo("订单处理中");
        assertThat(Long.parseLong((String) args[5])).isPositive();
        assertThat(args[6]).isEqualTo(String.valueOf(TTL_SECONDS));
    }

    @Test
    void markPersistedWritesPersistedState() {
        AtomicReference<Object[]> argsRef = new AtomicReference<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    argsRef.set(extractLuaArgs(invocation));
                    return 0L;
                });

        repository.markPersisted(order(1001L, 1L, 2L));

        Object[] args = argsRef.get();
        assertThat(args[3]).isEqualTo("PERSISTED");
        assertThat(args[4]).isEqualTo("订单已确认落库");
        assertThat(args[6]).isEqualTo(String.valueOf(TTL_SECONDS));
    }

    @Test
    void markFailedWritesFailedStateWithCallerSafeMessage() {
        AtomicReference<Object[]> argsRef = new AtomicReference<>();
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    argsRef.set(extractLuaArgs(invocation));
                    return 0L;
                });

        repository.markFailed(order(1001L, 1L, 2L), "订单处理失败，请稍后重试或联系客服");

        Object[] args = argsRef.get();
        assertThat(args[3]).isEqualTo("FAILED");
        assertThat(args[4]).isEqualTo("订单处理失败，请稍后重试或联系客服");
    }

    @Test
    void nullScriptResultFailsClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        assertThatThrownBy(() -> repository.markProcessing(order(1001L, 1L, 2L)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void identityConflictReturnCodeFailsClosed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(2L);

        assertThatThrownBy(() -> repository.markPersisted(order(1001L, 1L, 2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("身份");
    }

    @Test
    void unexpectedReturnCodeFailsClosed() {
        for (Long code : new Long[]{1L, 3L, 99L}) {
            when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(code);

            assertThatThrownBy(() -> repository.markFailed(order(1001L, 1L, 2L), "订单处理失败，请稍后重试或联系客服"))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void corruptionReturnCodeThreeFailsClosedWithCorruptionMessage() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(3L);

        assertThatThrownBy(() -> repository.markProcessing(order(1001L, 1L, 2L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("字段损坏");
    }

    @Test
    void stateParseRejectsUnknownAndUnknownValues() {
        assertThatThrownBy(() -> OrderSubmissionState.parse("UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderSubmissionState.parse("CANCELLED"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderSubmissionState.parse(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OrderSubmissionState.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void redisExceptionPropagatesNotSwallowed() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new RedisSystemException("redis down", new RuntimeException("connection refused")));

        assertThatThrownBy(() -> repository.markProcessing(order(1001L, 1L, 2L)))
                .isInstanceOf(RedisSystemException.class)
                .hasMessageContaining("redis down");
    }

    @Test
    void findReturnsEmptyWhenNoRecord() {
        when(hashOps.entries("transaction:order:submission:1001")).thenReturn(new HashMap<>());

        assertThat(repository.find(1001L)).isEmpty();
    }

    @Test
    void findParsesValidRecord() {
        Map<Object, Object> fields = new HashMap<>();
        fields.put("state", "ACCEPTED");
        fields.put("userId", "1");
        fields.put("voucherId", "2");
        fields.put("message", "订单已受理，等待处理");
        fields.put("updatedAt", "123456789");
        when(hashOps.entries("transaction:order:submission:1001")).thenReturn(fields);

        Optional<OrderSubmissionRecord> record = repository.find(1001L);

        assertThat(record).isPresent();
        OrderSubmissionRecord value = record.get();
        assertThat(value.orderId()).isEqualTo(1001L);
        assertThat(value.state()).isEqualTo(OrderSubmissionState.ACCEPTED);
        assertThat(value.userId()).isEqualTo(1L);
        assertThat(value.voucherId()).isEqualTo(2L);
        assertThat(value.message()).isEqualTo("订单已受理，等待处理");
        assertThat(value.updatedAt()).isEqualTo(123456789L);
    }

    @Test
    void findRejectsMissingField() {
        Map<Object, Object> fields = new HashMap<>();
        fields.put("state", "ACCEPTED");
        fields.put("userId", "1");
        fields.put("voucherId", "2");
        fields.put("updatedAt", "123456789");
        when(hashOps.entries("transaction:order:submission:1001")).thenReturn(fields);

        assertThatThrownBy(() -> repository.find(1001L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void findRejectsNonNumericUserId() {
        Map<Object, Object> fields = new HashMap<>();
        fields.put("state", "ACCEPTED");
        fields.put("userId", "abc");
        fields.put("voucherId", "2");
        fields.put("message", "订单已受理，等待处理");
        fields.put("updatedAt", "123456789");
        when(hashOps.entries("transaction:order:submission:1001")).thenReturn(fields);

        assertThatThrownBy(() -> repository.find(1001L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void findRejectsNonNumericUpdatedAt() {
        Map<Object, Object> fields = new HashMap<>();
        fields.put("state", "ACCEPTED");
        fields.put("userId", "1");
        fields.put("voucherId", "2");
        fields.put("message", "订单已受理，等待处理");
        fields.put("updatedAt", "not-a-number");
        when(hashOps.entries("transaction:order:submission:1001")).thenReturn(fields);

        assertThatThrownBy(() -> repository.find(1001L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void findRejectsUnknownState() {
        Map<Object, Object> fields = new HashMap<>();
        fields.put("state", "CANCELLED");
        fields.put("userId", "1");
        fields.put("voucherId", "2");
        fields.put("message", "订单已受理，等待处理");
        fields.put("updatedAt", "123456789");
        when(hashOps.entries("transaction:order:submission:1001")).thenReturn(fields);

        assertThatThrownBy(() -> repository.find(1001L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void findReturnsImmutableRecordNotMutableState() {
        Map<Object, Object> fields = new HashMap<>();
        fields.put("state", "FAILED");
        fields.put("userId", "1");
        fields.put("voucherId", "2");
        fields.put("message", "订单处理失败，请稍后重试或联系客服");
        fields.put("updatedAt", "1");
        when(hashOps.entries("transaction:order:submission:1001")).thenReturn(fields);

        Optional<OrderSubmissionRecord> record = repository.find(1001L);

        assertThat(record).isPresent();
        assertThat(record.get().state()).isEqualTo(OrderSubmissionState.FAILED);
        assertThat(record.get().getClass().isRecord()).isTrue();
    }

    @Test
    void repositoryHasNoForbiddenDependencies() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/submission/RedisOrderSubmissionStatusRepository.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("import com.linklife.trade.mapper.VoucherOrderMapper")
                .doesNotContain("import com.linklife.shared.api.Result")
                .doesNotContain("import com.linklife.identity.security.UserHolder")
                .doesNotContain("import com.linklife.trade.controller.")
                .doesNotContain("import com.linklife.trade.application.OrderQueryService");
    }
}
