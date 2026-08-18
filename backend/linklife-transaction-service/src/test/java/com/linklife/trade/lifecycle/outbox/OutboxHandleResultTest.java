package com.linklife.trade.lifecycle.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OutboxHandleResult 契约测试：SUCCESS 合法、失败错误码合法、非法组合拒绝。
 */
class OutboxHandleResultTest {

    @Test
    void successIsValidWithoutErrorCode() {
        OutboxHandleResult result = OutboxHandleResult.success();

        assertThat(result.type()).isEqualTo(OutboxHandleResult.OutboxHandleResultType.SUCCESS);
        assertThat(result.errorCode()).isNull();
    }

    @Test
    void retryableAndFatalCarryStableErrorCode() {
        assertThat(OutboxHandleResult.retryable("HANDLER_EXCEPTION").errorCode())
                .isEqualTo("HANDLER_EXCEPTION");
        assertThat(OutboxHandleResult.fatal("COMPENSATION_FAILED").type())
                .isEqualTo(OutboxHandleResult.OutboxHandleResultType.FATAL_FAILURE);
    }

    @Test
    void blankErrorCodeIsRejected() {
        assertThatThrownBy(() -> OutboxHandleResult.retryable(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxHandleResult.fatal(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxHandleResult.retryable("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlongErrorCodeIsRejected() {
        String longCode = "A".repeat(65);
        assertThatThrownBy(() -> OutboxHandleResult.retryable(longCode))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void illegalCharactersAreRejected() {
        assertThatThrownBy(() -> OutboxHandleResult.retryable("lowercase"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxHandleResult.retryable("WITH SPACE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxHandleResult.fatal("错误码"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void successWithErrorCodeIsRejected() {
        assertThatThrownBy(() -> new OutboxHandleResult(
                OutboxHandleResult.OutboxHandleResultType.SUCCESS, "CODE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullTypeIsRejected() {
        assertThatThrownBy(() -> new OutboxHandleResult(null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
