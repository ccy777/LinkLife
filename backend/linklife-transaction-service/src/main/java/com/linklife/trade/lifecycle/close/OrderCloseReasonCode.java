package com.linklife.trade.lifecycle.close;

/**
 * 订单关闭稳定原因码（写状态日志 reason_code 与 reason_detail 使用稳定文案）。
 */
public enum OrderCloseReasonCode {
    /** 用户主动取消 */
    USER_CANCEL,
    /** 支付超时自动关闭 */
    TIMEOUT_EXPIRED
}
