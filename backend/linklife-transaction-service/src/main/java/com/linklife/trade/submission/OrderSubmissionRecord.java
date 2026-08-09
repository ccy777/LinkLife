package com.linklife.trade.submission;

/**
 * Redis 订单提交状态记录的不可变视图，用于查询时的当前用户隔离判断。
 */
public record OrderSubmissionRecord(
        long orderId,
        OrderSubmissionState state,
        long userId,
        long voucherId,
        String message,
        long updatedAt) {
}
