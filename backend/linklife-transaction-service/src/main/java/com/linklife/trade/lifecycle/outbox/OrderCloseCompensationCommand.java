package com.linklife.trade.lifecycle.outbox;

import java.time.LocalDateTime;

/**
 * 订单关闭 Redis 库存补偿命令（不可变）。
 *
 * <p>校验：三个 ID 均大于 0；eventId/businessKey 非空且长度合法；
 * businessKey 必须等于 {@code VOUCHER_ORDER:CLOSED:{orderId}:V1}；
 * eventVersion 必须为 1；handledAt 非空且为秒级（nano==0）。</p>
 *
 * @param orderId      订单 ID
 * @param userId       用户 ID
 * @param voucherId    秒杀券 ID
 * @param eventId      Outbox 事件 ID
 * @param businessKey  确定性业务键
 * @param eventVersion 事件版本（当前固定 1）
 * @param handledAt    处理时间（秒级）
 */
public record OrderCloseCompensationCommand(
        long orderId,
        long userId,
        long voucherId,
        String eventId,
        String businessKey,
        int eventVersion,
        LocalDateTime handledAt) {

    private static final String BUSINESS_KEY_PREFIX = "VOUCHER_ORDER:CLOSED:";
    private static final String BUSINESS_KEY_SUFFIX = ":V1";

    public OrderCloseCompensationCommand {
        if (orderId <= 0 || userId <= 0 || voucherId <= 0) {
            throw new IllegalArgumentException("orderId/userId/voucherId 必须大于 0");
        }
        if (eventId == null || eventId.isBlank() || eventId.length() > 64) {
            throw new IllegalArgumentException("eventId 必须非空且长度不超过 64");
        }
        if (businessKey == null
                || !businessKey.equals(BUSINESS_KEY_PREFIX + orderId + BUSINESS_KEY_SUFFIX)) {
            throw new IllegalArgumentException("businessKey 必须等于 VOUCHER_ORDER:CLOSED:{orderId}:V1");
        }
        if (eventVersion != 1) {
            throw new IllegalArgumentException("eventVersion 必须为 1");
        }
        if (handledAt == null || handledAt.getNano() != 0) {
            throw new IllegalArgumentException("handledAt 必须非空且为秒级");
        }
    }
}
