package com.linklife.trade.lifecycle;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * VoucherOrderStatus 单元测试：1—6 精确映射、code 唯一、非法值 fail-closed、
 * 不混入提交状态枚举值。纯 Java，不连接 Redis/MySQL。
 */
class VoucherOrderStatusTest {

    @Test
    void exactCodeMappingOneToSix() {
        assertThat(VoucherOrderStatus.UNPAID.getCode()).isEqualTo(1);
        assertThat(VoucherOrderStatus.PAID.getCode()).isEqualTo(2);
        assertThat(VoucherOrderStatus.USED.getCode()).isEqualTo(3);
        assertThat(VoucherOrderStatus.CANCELED.getCode()).isEqualTo(4);
        assertThat(VoucherOrderStatus.REFUNDING.getCode()).isEqualTo(5);
        assertThat(VoucherOrderStatus.REFUNDED.getCode()).isEqualTo(6);

        assertThat(VoucherOrderStatus.fromCode(1)).isEqualTo(VoucherOrderStatus.UNPAID);
        assertThat(VoucherOrderStatus.fromCode(2)).isEqualTo(VoucherOrderStatus.PAID);
        assertThat(VoucherOrderStatus.fromCode(3)).isEqualTo(VoucherOrderStatus.USED);
        assertThat(VoucherOrderStatus.fromCode(4)).isEqualTo(VoucherOrderStatus.CANCELED);
        assertThat(VoucherOrderStatus.fromCode(5)).isEqualTo(VoucherOrderStatus.REFUNDING);
        assertThat(VoucherOrderStatus.fromCode(6)).isEqualTo(VoucherOrderStatus.REFUNDED);
    }

    @Test
    void codesAreUnique() {
        Set<Integer> codes = Arrays.stream(VoucherOrderStatus.values())
                .map(VoucherOrderStatus::getCode)
                .collect(Collectors.toSet());
        assertThat(codes).hasSize(6);
    }

    @Test
    void nullZeroNegativeSevenAndUnknownFailClosed() {
        assertThatThrownBy(() -> VoucherOrderStatus.fromCode((Integer) null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoucherOrderStatus.fromCode(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoucherOrderStatus.fromCode(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoucherOrderStatus.fromCode(7))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VoucherOrderStatus.fromCode(99))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void submissionStatesAreNeverMixedIn() {
        assertThat(Arrays.stream(VoucherOrderStatus.values())
                .map(Enum::name)
                .toList())
                .doesNotContain("ACCEPTED", "PROCESSING", "PERSISTED", "FAILED", "UNKNOWN");
        assertThat(VoucherOrderStatus.values()).hasSize(6);
    }
}
