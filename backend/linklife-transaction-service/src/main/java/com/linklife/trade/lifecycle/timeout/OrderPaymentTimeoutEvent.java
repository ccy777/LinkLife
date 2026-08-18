package com.linklife.trade.lifecycle.timeout;

/**
 * 未支付订单 timeout-check 事件契约常量。
 */
public final class OrderPaymentTimeoutEvent {

    public static final String AGGREGATE_TYPE = "VOUCHER_ORDER";
    public static final String EVENT_TYPE = "ORDER_PAYMENT_TIMEOUT_CHECK";
    public static final int EVENT_VERSION = 1;
    public static final String BUSINESS_KEY_PREFIX = "VOUCHER_ORDER:PAYMENT_TIMEOUT_CHECK:";
    public static final String BUSINESS_KEY_SUFFIX = ":V1";

    private OrderPaymentTimeoutEvent() {
    }

    public static String businessKey(long orderId) {
        return BUSINESS_KEY_PREFIX + orderId + BUSINESS_KEY_SUFFIX;
    }
}
