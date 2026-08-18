package com.linklife.trade.lifecycle.outbox;

import java.time.LocalDateTime;

/**
 * 秒杀券 Redis 原子初始化命令（不可变）。
 *
 * <p>校验：voucherId 规范正整数；initialStock 0..Integer.MAX_VALUE；
 * begin/end epoch 毫秒规范正整数且 begin&lt;end；eventId/businessKey 非空；
 * handledAt 非空且秒级；version 固定 1。</p>
 */
public record SeckillVoucherInitializeCommand(
        long voucherId,
        int initialStock,
        long beginEpochMillis,
        long endEpochMillis,
        String eventId,
        String businessKey,
        LocalDateTime handledAt,
        int eventVersion) {

    public SeckillVoucherInitializeCommand {
        if (voucherId <= 0) {
            throw new IllegalArgumentException("voucherId 必须大于 0");
        }
        if (initialStock < 0) {
            throw new IllegalArgumentException("initialStock 不能小于 0");
        }
        if (beginEpochMillis <= 0 || endEpochMillis <= 0) {
            throw new IllegalArgumentException("begin/end epoch 毫秒必须为正整数");
        }
        if (beginEpochMillis >= endEpochMillis) {
            throw new IllegalArgumentException("beginEpochMillis 必须小于 endEpochMillis");
        }
        if (eventId == null || eventId.isBlank() || eventId.length() > 64) {
            throw new IllegalArgumentException("eventId 必须非空且不超过 64");
        }
        if (businessKey == null || businessKey.isBlank() || businessKey.length() > 96) {
            throw new IllegalArgumentException("businessKey 必须非空且不超过 96");
        }
        if (handledAt == null || handledAt.getNano() != 0) {
            throw new IllegalArgumentException("handledAt 必须非空且为秒级");
        }
        if (eventVersion != 1) {
            throw new IllegalArgumentException("eventVersion 必须为 1");
        }
    }
}
