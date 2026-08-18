package com.linklife.trade.lifecycle.timeout;

import java.time.Instant;

/**
 * 单轮超时关闭的不可变汇总结果。
 *
 * @param dueAtCutoff   本轮固定的 payment_due_at 截止绝对时刻
 * @param batches       本轮实际处理的批次数量
 * @param scanned       本轮扫描到的候选订单总数
 * @param closed        本轮成功 CAS 关闭（UNPAID → CANCELED）的订单数
 * @param skipped       本轮因并发推进或订单消失而未关闭的订单数（0 行更新不视为成功关闭）
 * @param limitReached  是否达到单轮最大批次数上限（true 时由下一轮继续处理剩余订单）
 */
public record OrderTimeoutCloseResult(
        Instant dueAtCutoff,
        int batches,
        int scanned,
        int closed,
        int skipped,
        boolean limitReached) {

    public OrderTimeoutCloseResult {
        if (batches < 0 || scanned < 0 || closed < 0 || skipped < 0) {
            throw new IllegalArgumentException("超时关闭结果计数不得为负");
        }
    }
}
