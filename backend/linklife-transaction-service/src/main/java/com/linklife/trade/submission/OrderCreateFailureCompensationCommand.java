package com.linklife.trade.submission;

import java.time.LocalDateTime;

/**
 * 订单创建失败 Redis 补偿命令（不可变）。
 *
 * <p>校验：三个 ID 均大于 0；mode 非空且与 existingOrderId 一致
 * （释放资格模式必须 existingOrderId=0，保留资格模式必须 existingOrderId&gt;0）；
 * handledAt 非空且为秒级（nano==0）；version 固定 1。</p>
 */
public record OrderCreateFailureCompensationCommand(
        long orderId,
        long userId,
        long voucherId,
        OrderCreateCompensationMode mode,
        long existingOrderId,
        LocalDateTime handledAt,
        int version) {

    public OrderCreateFailureCompensationCommand {
        if (orderId <= 0 || userId <= 0 || voucherId <= 0) {
            throw new IllegalArgumentException("orderId/userId/voucherId 必须大于 0");
        }
        if (mode == null) {
            throw new IllegalArgumentException("mode 不能为空");
        }
        if (mode == OrderCreateCompensationMode.RESTORE_STOCK_AND_RELEASE_QUALIFICATION) {
            if (existingOrderId != 0) {
                throw new IllegalArgumentException("释放资格模式要求 existingOrderId=0");
            }
        } else if (existingOrderId <= 0) {
            throw new IllegalArgumentException("保留资格模式要求 existingOrderId>0");
        }
        if (handledAt == null || handledAt.getNano() != 0) {
            throw new IllegalArgumentException("handledAt 必须非空且为秒级");
        }
        if (version != 1) {
            throw new IllegalArgumentException("version 必须为 1");
        }
    }
}
