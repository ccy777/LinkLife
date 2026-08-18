package com.linklife.trade.submission;

/**
 * 订单提交状态：描述异步提交过程（ACCEPTED/PROCESSING/PERSISTED/FAILED），
 * 与订单业务状态（1—6）严格区分。UNKNOWN 只用于查询返回，不写入 Redis。
 */
public enum OrderSubmissionState {
    ACCEPTED,
    PROCESSING,
    PERSISTED,
    FAILED,
    UNKNOWN;

    /**
     * 解析 Redis 中保存的状态名；空值、UNKNOWN 或未知值一律抛异常（fail-closed），
     * 不得伪造成 PERSISTED。
     */
    public static OrderSubmissionState parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("提交状态为空或非法：" + raw);
        }
        if ("UNKNOWN".equals(raw)) {
            throw new IllegalArgumentException("UNKNOWN 只用于查询返回，不允许写入 Redis：" + raw);
        }
        for (OrderSubmissionState value : values()) {
            if (value.name().equals(raw)) {
                return value;
            }
        }
        throw new IllegalArgumentException("未知提交状态：" + raw);
    }
}
