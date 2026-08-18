package com.linklife.trade.submission;

/**
 * 订单创建失败终态分类决策（不可变）。
 *
 * <p>对应任务书 3.4 终态事实分类：
 * CURRENT_ORDER_PERSISTED=当前 orderId 已存在且身份一致（A，不补偿，恢复 PERSISTED）；
 * CONFLICTING_OTHER_ORDER=同 user/voucher 已有不同 orderId（B，库存补偿已成功、资格保留）；
 * NO_MYSQL_ORDER=MySQL 完全无该 user/voucher 订单（C，库存+释放资格补偿已成功）；
 * RETRYABLE_COMPENSATION=补偿可重试失败（保留 Pending）；
 * FATAL_COMPENSATION=补偿致命失败（保留 Pending、人工处理）；
 * UNCERTAIN=MySQL 事实读取失败或身份异常（不补偿、保留 Pending）。</p>
 */
public record OrderCreationFailureDecision(DecisionType type) {

    public OrderCreationFailureDecision {
        if (type == null) {
            throw new IllegalArgumentException("决策类型不能为空");
        }
    }

    public static OrderCreationFailureDecision currentOrderPersisted() {
        return new OrderCreationFailureDecision(DecisionType.CURRENT_ORDER_PERSISTED);
    }

    public static OrderCreationFailureDecision conflictingOtherOrder() {
        return new OrderCreationFailureDecision(DecisionType.CONFLICTING_OTHER_ORDER);
    }

    public static OrderCreationFailureDecision noMySqlOrder() {
        return new OrderCreationFailureDecision(DecisionType.NO_MYSQL_ORDER);
    }

    public static OrderCreationFailureDecision retryableCompensation() {
        return new OrderCreationFailureDecision(DecisionType.RETRYABLE_COMPENSATION);
    }

    public static OrderCreationFailureDecision fatalCompensation() {
        return new OrderCreationFailureDecision(DecisionType.FATAL_COMPENSATION);
    }

    public static OrderCreationFailureDecision uncertain() {
        return new OrderCreationFailureDecision(DecisionType.UNCERTAIN);
    }

    public enum DecisionType {
        CURRENT_ORDER_PERSISTED,
        CONFLICTING_OTHER_ORDER,
        NO_MYSQL_ORDER,
        RETRYABLE_COMPENSATION,
        FATAL_COMPENSATION,
        UNCERTAIN
    }
}
