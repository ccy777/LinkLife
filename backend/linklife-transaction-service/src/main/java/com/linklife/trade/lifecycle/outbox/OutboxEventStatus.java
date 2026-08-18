package com.linklife.trade.lifecycle.outbox;

/**
 * 本地 Outbox 事件状态（冻结四态）。
 *
 * <p>解析 fail-closed：null、空或未知值一律抛异常，不得静默处理。</p>
 */
public enum OutboxEventStatus {
    /** 待处理 */
    PENDING,
    /** 已领取处理中（持有租约） */
    PROCESSING,
    /** 处理成功 */
    SUCCESS,
    /** 致命失败/达最大重试，人工介入 */
    DEAD;

    public static OutboxEventStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Outbox 状态为空或非法");
        }
        for (OutboxEventStatus status : values()) {
            if (status.name().equals(raw)) {
                return status;
            }
        }
        throw new IllegalArgumentException("未知 Outbox 状态: " + raw);
    }
}
