package com.linklife.trade.lifecycle;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * VoucherOrderTransitionPolicy 单元测试：唯一允许 UNPAID → CANCELED；
 * 其余状态拒绝；CANCELED 重复命令视为幂等而非新转换；未实现的支付/核销/退款转换无入口。
 */
class VoucherOrderTransitionPolicyTest {

    @Test
    void unpaidToCanceledIsAllowed() {
        assertThat(VoucherOrderTransitionPolicy.canTransitionToCanceled(VoucherOrderStatus.UNPAID)).isTrue();
    }

    @Test
    void paidUsedRefundingRefundedToCanceledAreRejected() {
        assertThat(VoucherOrderTransitionPolicy.canTransitionToCanceled(VoucherOrderStatus.PAID)).isFalse();
        assertThat(VoucherOrderTransitionPolicy.canTransitionToCanceled(VoucherOrderStatus.USED)).isFalse();
        assertThat(VoucherOrderTransitionPolicy.canTransitionToCanceled(VoucherOrderStatus.REFUNDING)).isFalse();
        assertThat(VoucherOrderTransitionPolicy.canTransitionToCanceled(VoucherOrderStatus.REFUNDED)).isFalse();
    }

    @Test
    void canceledDuplicateCommandIsIdempotentNotNewTransition() {
        assertThat(VoucherOrderTransitionPolicy.isCanceled(VoucherOrderStatus.CANCELED)).isTrue();
        assertThat(VoucherOrderTransitionPolicy.canTransitionToCanceled(VoucherOrderStatus.CANCELED)).isFalse();
    }

    @Test
    void unimplementedPaymentUseAndRefundTransitionsHaveNoEntry() {
        for (VoucherOrderStatus status : VoucherOrderStatus.values()) {
            if (status != VoucherOrderStatus.UNPAID) {
                assertThat(VoucherOrderTransitionPolicy.canTransitionToCanceled(status))
                        .as("非 UNPAID 状态 %s 不得转换到 CANCELED", status)
                        .isFalse();
            }
        }
        assertThat(publicMethodNames())
                .containsExactlyInAnyOrder("canTransitionToCanceled", "isCanceled", "isNonCancelableProgressed");
    }

    @Test
    void unknownStateIsRejected() {
        assertThat(VoucherOrderTransitionPolicy.canTransitionToCanceled(null)).isFalse();
        assertThat(VoucherOrderTransitionPolicy.isCanceled(null)).isFalse();
        assertThat(VoucherOrderTransitionPolicy.isNonCancelableProgressed(null)).isFalse();
    }

    private java.util.List<String> publicMethodNames() {
        return Arrays.stream(VoucherOrderTransitionPolicy.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .map(Method::getName)
                .toList();
    }
}
