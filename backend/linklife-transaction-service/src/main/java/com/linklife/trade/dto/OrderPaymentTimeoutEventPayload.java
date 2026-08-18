package com.linklife.trade.dto;

import java.time.LocalDateTime;
import java.time.Instant;

/**
 * ORDER_PAYMENT_TIMEOUT_CHECK V1 payload。createdAt/dueAt 在订单创建事务内冻结，
 * Consumer 不得按消费时配置重新计算。
 */
public record OrderPaymentTimeoutEventPayload(
        String eventId,
        int eventVersion,
        long orderId,
        long userId,
        long voucherId,
        LocalDateTime createdAt,
        Instant createdAtInstant,
        Instant dueAt) {
}
