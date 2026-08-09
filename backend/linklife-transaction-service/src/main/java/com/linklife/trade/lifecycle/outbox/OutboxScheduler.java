package com.linklife.trade.lifecycle.outbox;

import com.linklife.trade.application.OutboxPollingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Outbox 轮询调度器：只负责调用 {@link OutboxPollingService} 并记录结构化汇总日志。
 *
 * <p>自身不 catch 服务异常，异常向 Spring Scheduling 错误处理器传播；
 * 不声称业务代码自行记录自定义失败日志；不吞掉、不转换为成功。
 * 调度任务仅在 {@code linklife.trade.outbox.enabled=true} 时由配置类创建。</p>
 */
@Slf4j
public class OutboxScheduler {

    private final OutboxPollingService outboxPollingService;

    public OutboxScheduler(OutboxPollingService outboxPollingService) {
        this.outboxPollingService = outboxPollingService;
    }

    /**
     * 固定延时调度入口，不接收 Web 参数。
     */
    @Scheduled(
            fixedDelayString = "${linklife.trade.outbox.scan-delay-ms}",
            initialDelayString = "${linklife.trade.outbox.initial-delay-ms}")
    public void pollDueEvents() {
        OutboxPollResult result = outboxPollingService.pollDueEvents();
        log.info("Outbox 轮询完成 batches={} scanned={} claimed={} succeeded={} retried={} "
                        + "dead={} skipped={} leaseLost={} limitReached={}",
                result.batches(), result.scanned(), result.claimed(), result.succeeded(),
                result.retried(), result.dead(), result.skipped(), result.leaseLost(),
                result.limitReached());
    }
}
