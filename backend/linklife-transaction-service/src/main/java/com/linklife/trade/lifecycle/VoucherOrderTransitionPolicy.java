package com.linklife.trade.lifecycle;

/**
 * 订单状态转换策略：最小、显式的业务转换集合，纯 Java 逻辑，便于单元测试。
 *
 * <p>本阶段（016A）唯一允许的业务转换是 {@code UNPAID → CANCELED}；其余状态到 CANCELED
 * 一律拒绝。已取消订单收到重复取消命令时视为幂等结果，不构成一次新的状态转换。</p>
 *
 * <p>支付、核销、退款等转换尚未实现，本类不提供任何对应入口；未知/空状态一律拒绝。</p>
 */
public final class VoucherOrderTransitionPolicy {

    private VoucherOrderTransitionPolicy() {
    }

    /**
     * 是否允许从当前状态转换到 CANCELED。只有 UNPAID 允许；
     * CANCELED 重复取消属于幂等命令，不在此方法允许范围内。
     */
    public static boolean canTransitionToCanceled(VoucherOrderStatus current) {
        return current == VoucherOrderStatus.UNPAID;
    }

    /**
     * 当前状态是否为已取消：用于取消命令的幂等成功判定，不作为一次新的状态转换。
     */
    public static boolean isCanceled(VoucherOrderStatus current) {
        return current == VoucherOrderStatus.CANCELED;
    }

    /**
     * 业务已推进（已支付/已核销/退款中/已退款）的订单不可取消。
     */
    public static boolean isNonCancelableProgressed(VoucherOrderStatus current) {
        return current == VoucherOrderStatus.PAID
                || current == VoucherOrderStatus.USED
                || current == VoucherOrderStatus.REFUNDING
                || current == VoucherOrderStatus.REFUNDED;
    }
}
