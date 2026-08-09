package com.linklife.trade.lifecycle.timeout;

import com.linklife.trade.application.OrderTimeoutCloseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 超时订单关闭调度器：只负责调用 {@link OrderTimeoutCloseService} 并记录结构化汇总日志。
 *
 * <p>本类不访问 Mapper、Redis、库存服务或 UserHolder；调度任务仅在
 * {@code linklife.trade.order-timeout.enabled=true} 时由配置类创建。</p>
 */
@Slf4j
public class OrderTimeoutCloseScheduler {

    private final OrderTimeoutCloseService orderTimeoutCloseService;

    public OrderTimeoutCloseScheduler(OrderTimeoutCloseService orderTimeoutCloseService) {
        this.orderTimeoutCloseService = orderTimeoutCloseService;
    }

    /**
     * 固定延时调度入口，不接收 Web 参数。
     */
    @Scheduled(
            fixedDelayString = "${linklife.trade.order-timeout.scan-delay-ms}",
            initialDelayString = "${linklife.trade.order-timeout.initial-delay-ms}")
    public void closeExpiredOrders() {
        OrderTimeoutCloseResult result = orderTimeoutCloseService.closeExpiredOrders();
        log.info("超时订单关闭完成 cutoff={} batches={} scanned={} closed={} skipped={} limitReached={}",
                result.cutoff(), result.batches(), result.scanned(),
                result.closed(), result.skipped(), result.limitReached());
    }
}
