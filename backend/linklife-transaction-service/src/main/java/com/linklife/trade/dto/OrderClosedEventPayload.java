package com.linklife.trade.dto;

import java.time.LocalDateTime;

/**
 * ORDER_CLOSED Outbox 事件 payload（最小必要字段，字段集合与含义为契约，顺序不作为契约）。
 *
 * <p>不包含优惠券标题、用户昵称、SQL、异常堆栈或任何秘密。</p>
 */
public record OrderClosedEventPayload(
        String eventId,
        int eventVersion,
        long orderId,
        long userId,
        long voucherId,
        int toStatus,
        String triggerType,
        LocalDateTime closedAt) {
}
