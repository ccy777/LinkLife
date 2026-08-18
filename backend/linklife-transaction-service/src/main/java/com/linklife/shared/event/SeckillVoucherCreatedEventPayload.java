package com.linklife.shared.event;

import java.time.LocalDateTime;

/**
 * SECKILL_VOUCHER_CREATED Outbox 事件 payload（shared 通用数据端口）。
 *
 * <p>时间统一秒级；initialStock 为创建时 MySQL 秒杀券库存；begin/end 为 epoch milliseconds。</p>
 */
public record SeckillVoucherCreatedEventPayload(
        String eventId,
        int eventVersion,
        long voucherId,
        int initialStock,
        long beginEpochMillis,
        long endEpochMillis,
        LocalDateTime createdAt) {
}
