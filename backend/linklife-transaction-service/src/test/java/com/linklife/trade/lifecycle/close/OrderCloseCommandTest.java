package com.linklife.trade.lifecycle.close;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrderCloseCommand 领域契约测试：两种合法命令、非法输入与 trigger/reason 匹配校验。
 */
class OrderCloseCommandTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 6, 10, 0, 0);
    private static final Instant CUTOFF = Instant.parse("2026-08-06T10:00:00Z");

    @Test
    void userCancelCommandIsValid() {
        OrderCloseCommand command = new OrderCloseCommand(
                1001L, 1L, OrderCloseTriggerType.USER_CANCEL, null,
                OrderCloseReasonCode.USER_CANCEL, NOW);

        assertThat(command.orderId()).isEqualTo(1001L);
        assertThat(command.userId()).isEqualTo(1L);
        assertThat(command.dueAtCutoff()).isNull();
        assertThat(command.reasonCode()).isEqualTo(OrderCloseReasonCode.USER_CANCEL);
    }

    @Test
    void timeoutCloseCommandIsValid() {
        OrderCloseCommand command = new OrderCloseCommand(
                1001L, null, OrderCloseTriggerType.TIMEOUT_CLOSE, CUTOFF,
                OrderCloseReasonCode.TIMEOUT_EXPIRED, NOW);

        assertThat(command.userId()).isNull();
        assertThat(command.dueAtCutoff()).isEqualTo(CUTOFF);
    }

    @Test
    void nonPositiveOrderIdIsRejected() {
        assertThatThrownBy(() -> new OrderCloseCommand(
                0L, 1L, OrderCloseTriggerType.USER_CANCEL, null,
                OrderCloseReasonCode.USER_CANCEL, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OrderCloseCommand(
                -1L, 1L, OrderCloseTriggerType.USER_CANCEL, null,
                OrderCloseReasonCode.USER_CANCEL, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void userCancelWithoutUserIdIsRejected() {
        assertThatThrownBy(() -> new OrderCloseCommand(
                1001L, null, OrderCloseTriggerType.USER_CANCEL, null,
                OrderCloseReasonCode.USER_CANCEL, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void timeoutCloseWithUserIdIsRejected() {
        assertThatThrownBy(() -> new OrderCloseCommand(
                1001L, 1L, OrderCloseTriggerType.TIMEOUT_CLOSE, CUTOFF,
                OrderCloseReasonCode.TIMEOUT_EXPIRED, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId");
    }

    @Test
    void timeoutCloseWithoutCutoffIsRejected() {
        assertThatThrownBy(() -> new OrderCloseCommand(
                1001L, null, OrderCloseTriggerType.TIMEOUT_CLOSE, null,
                OrderCloseReasonCode.TIMEOUT_EXPIRED, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dueAtCutoff");
    }

    @Test
    void userCancelWithCutoffIsRejected() {
        assertThatThrownBy(() -> new OrderCloseCommand(
                1001L, 1L, OrderCloseTriggerType.USER_CANCEL, CUTOFF,
                OrderCloseReasonCode.USER_CANCEL, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dueAtCutoff");
    }

    @Test
    void mismatchedTriggerAndReasonAreRejected() {
        assertThatThrownBy(() -> new OrderCloseCommand(
                1001L, 1L, OrderCloseTriggerType.USER_CANCEL, null,
                OrderCloseReasonCode.TIMEOUT_EXPIRED, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");
        assertThatThrownBy(() -> new OrderCloseCommand(
                1001L, null, OrderCloseTriggerType.TIMEOUT_CLOSE, CUTOFF,
                OrderCloseReasonCode.USER_CANCEL, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不匹配");
    }

    @Test
    void closeResultEnumIsComplete() {
        assertThat(OrderCloseResult.values())
                .containsExactly(OrderCloseResult.CLOSED, OrderCloseResult.ALREADY_CANCELED,
                        OrderCloseResult.NOT_FOUND, OrderCloseResult.NOT_CLOSABLE,
                        OrderCloseResult.DATA_INCONSISTENT);
    }
}
