package com.linklife.trade.lifecycle;

/**
 * 订单业务状态：固定映射 {@code tb_voucher_order.status} 的 1—6。
 *
 * <p>与提交状态（{@code OrderSubmissionState}：ACCEPTED/PROCESSING/PERSISTED/FAILED/UNKNOWN）
 * 严格区分：本枚举只描述已落库订单的业务状态，不混入异步提交过程状态。</p>
 *
 * <p>{@link #fromCode(Integer)} 采用 fail-closed 语义：null、0、负数、7 及其他未知值
 * 一律拒绝，不得静默转换为任何业务状态。</p>
 */
public enum VoucherOrderStatus {
    UNPAID(1),
    PAID(2),
    USED(3),
    CANCELED(4),
    REFUNDING(5),
    REFUNDED(6);

    private final int code;

    VoucherOrderStatus(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static VoucherOrderStatus fromCode(Integer code) {
        if (code == null) {
            throw new IllegalArgumentException("订单状态为空");
        }
        return fromCode(code.intValue());
    }

    public static VoucherOrderStatus fromCode(int code) {
        for (VoucherOrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知订单状态码: " + code);
    }
}
