package com.linklife.trade.lifecycle.outbox;

/**
 * 单轮 Outbox 轮询汇总结果（不可变，计数非负）。
 *
 * @param batches       实际处理批次数量
 * @param scanned       本轮扫描到的候选事件总数（含 expired PROCESSING）
 * @param claimed       领取 CAS 成功并进入 handler 的事件数（expired PROCESSING 直接 DEAD 不计 claimed）
 * @param succeeded     处理成功数
 * @param retried       可重试失败回 PENDING 数
 * @param dead          进入 DEAD 数（含 FATAL、达上限、租约过期直接 DEAD）
 * @param skipped       并发被其他实例处理/领取失败的跳过数
 * @param leaseLost     handler 已执行但 token 守卫更新 0 行（租约丢失）数
 * @param limitReached  是否达到单轮最大批次数
 */
public record OutboxPollResult(
        int batches,
        int scanned,
        int claimed,
        int succeeded,
        int retried,
        int dead,
        int skipped,
        int leaseLost,
        boolean limitReached) {

    public OutboxPollResult {
        if (batches < 0 || scanned < 0 || claimed < 0 || succeeded < 0
                || retried < 0 || dead < 0 || skipped < 0 || leaseLost < 0) {
            throw new IllegalArgumentException("Outbox 轮询结果计数不得为负");
        }
    }
}
