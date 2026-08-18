package com.linklife.trade.application;

import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.trade.lifecycle.close.OrderCloseCommand;
import com.linklife.trade.lifecycle.close.OrderCloseReasonCode;
import com.linklife.trade.lifecycle.close.OrderCloseResult;
import com.linklife.trade.lifecycle.close.OrderCloseTriggerType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderLifecycleService 单元测试（017D 接入统一关闭事务内核后）：
 * 命令构造、userId 来自服务端上下文、固定 now、结果穷尽映射、异常原样传播、
 * 未登录/非法 orderId 语义保持、不再直接访问 Mapper、不触碰 Redis/SREM/Outbox。
 * 真实 MySQL 事务与并发语义由后续 017G 隔离集成验证。
 */
class OrderLifecycleServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

    private OrderLifecycleService service;
    private OrderCloseTransactionService closeService;

    @BeforeEach
    void setUp() {
        service = new OrderLifecycleService();
        closeService = mock(OrderCloseTransactionService.class);
        ReflectionTestUtils.setField(service, "orderCloseTransactionService", closeService);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void asUser(long userId) {
        UserContext.set(userId);
    }

    private OrderCloseCommand capturedCommand() {
        ArgumentCaptor<OrderCloseCommand> captor = ArgumentCaptor.forClass(OrderCloseCommand.class);
        verify(closeService).close(captor.capture());
        return captor.getValue();
    }

    @Test
    void userCancelBuildsExactCommand() {
        asUser(1L);
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.CLOSED);

        service.cancelByCurrentUser(1001L);

        OrderCloseCommand command = capturedCommand();
        assertThat(command.orderId()).isEqualTo(1001L);
        assertThat(command.userId()).isEqualTo(1L);
        assertThat(command.triggerType()).isEqualTo(OrderCloseTriggerType.USER_CANCEL);
        assertThat(command.dueAtCutoff()).isNull();
        assertThat(command.reasonCode()).isEqualTo(OrderCloseReasonCode.USER_CANCEL);
        assertThat(command.now()).isEqualTo(FIXED_NOW);
    }

    @Test
    void commandUserIdComesFromUserHolder() {
        asUser(2L);
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.ALREADY_CANCELED);

        service.cancelByCurrentUser(1001L);

        assertThat(capturedCommand().userId()).isEqualTo(2L);
    }

    @Test
    void commandNowUsesFixedTestableClock() {
        asUser(1L);
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.CLOSED);

        service.cancelByCurrentUser(1001L);

        assertThat(capturedCommand().now()).isEqualTo(FIXED_NOW);
    }

    @Test
    void closedReturnsOrderId() {
        asUser(1L);
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.CLOSED);

        assertThat(service.cancelByCurrentUser(1001L)).isEqualTo(1001L);
    }

    @Test
    void alreadyCanceledIsIdempotentSuccess() {
        asUser(1L);
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.ALREADY_CANCELED);

        assertThat(service.cancelByCurrentUser(1001L)).isEqualTo(1001L);
    }

    @Test
    void notFoundMapsToOrderNotExist() {
        asUser(1L);
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.NOT_FOUND);

        assertThatThrownBy(() -> service.cancelByCurrentUser(1001L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(OrderLifecycleService.ORDER_NOT_FOUND);
    }

    @Test
    void notClosableMapsToStatusNotCancelable() {
        asUser(1L);
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.NOT_CLOSABLE);

        assertThatThrownBy(() -> service.cancelByCurrentUser(1001L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(OrderLifecycleService.STATUS_NOT_CANCELABLE);
    }

    @Test
    void dataInconsistentFailsClosed() {
        asUser(1L);
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.DATA_INCONSISTENT);

        assertThatThrownBy(() -> service.cancelByCurrentUser(1001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    void kernelExceptionPropagatesUnchanged() {
        asUser(1L);
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.cancelByCurrentUser(1001L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }

    @Test
    void notLoggedInFailsClosedWithoutTouchingKernel() {
        assertThatThrownBy(() -> service.cancelByCurrentUser(1001L))
                .isInstanceOf(BusinessException.class)
                .hasMessage(OrderLifecycleService.NOT_LOGGED_IN);
        verify(closeService, never()).close(any(OrderCloseCommand.class));
    }

    @Test
    void nonPositiveOrderIdRejectedWithoutTouchingKernel() {
        asUser(1L);

        assertThatThrownBy(() -> service.cancelByCurrentUser(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.cancelByCurrentUser(-5))
                .isInstanceOf(IllegalArgumentException.class);
        verify(closeService, never()).close(any(OrderCloseCommand.class));
    }

    @Test
    void serviceNoLongerDirectlyAccessesOrderMapper() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/application/OrderLifecycleService.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("voucherOrderMapper")
                .doesNotContain("VoucherOrderMapper")
                .doesNotContain("LambdaUpdateWrapper")
                .doesNotContain("selectOne");
        assertThat(java.util.Arrays.stream(OrderLifecycleService.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getType)
                .map(Class::getName))
                .doesNotContain("com.linklife.trade.mapper.VoucherOrderMapper");
    }

    @Test
    void serviceDoesNotAcceptClientUserId() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/application/OrderLifecycleService.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("com.linklife.trade.controller")
                .doesNotContain("@RequestBody")
                .doesNotContain("HttpServletRequest");
    }

    @Test
    void serviceDoesNotTouchRedisSremOrOutboxMapper() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/application/OrderLifecycleService.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("StringRedisTemplate")
                .doesNotContain("RedisConstants")
                .doesNotContain("SREM")
                .doesNotContain("OutboxEventMapper")
                .doesNotContain("RedissonClient");
    }
}
