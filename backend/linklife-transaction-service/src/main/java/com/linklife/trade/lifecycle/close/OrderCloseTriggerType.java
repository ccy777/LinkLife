package com.linklife.trade.lifecycle.close;

/**
 * 订单关闭触发来源（审计属性，不进入状态迁移唯一键）。
 */
public enum OrderCloseTriggerType {
    /** 用户主动取消 */
    USER_CANCEL,
    /** 系统超时自动关闭 */
    TIMEOUT_CLOSE
}
