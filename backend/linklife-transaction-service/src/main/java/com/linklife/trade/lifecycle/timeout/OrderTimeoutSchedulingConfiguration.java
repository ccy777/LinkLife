package com.linklife.trade.lifecycle.timeout;

import com.linklife.trade.application.OrderTimeoutCloseService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 最小调度配置：启用 Spring Scheduling 基础设施，但超时关闭调度任务只有在
 * {@code linklife.trade.order-timeout.enabled=true} 时才创建（不设置 matchIfMissing，
 * 默认缺失/非 true 均不激活）。默认配置下不创建任何超时关闭调度任务。
 */
@Configuration
@EnableScheduling
public class OrderTimeoutSchedulingConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "linklife.trade.order-timeout",
            name = "enabled",
            havingValue = "true")
    public OrderTimeoutCloseScheduler orderTimeoutCloseScheduler(OrderTimeoutCloseService orderTimeoutCloseService) {
        return new OrderTimeoutCloseScheduler(orderTimeoutCloseService);
    }
}
