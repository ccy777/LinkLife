package com.linklife.trade.lifecycle.outbox;

import com.linklife.trade.application.OutboxPollingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Outbox 调度配置：仅在 {@code linklife.trade.outbox.enabled=true} 时创建调度器
 * （不设置 matchIfMissing，缺失/非 true 均不激活）。默认配置下不创建 Outbox 调度任务。
 */
@Configuration
@EnableScheduling
public class OutboxSchedulingConfiguration {

    @Bean
    @ConditionalOnProperty(
            prefix = "linklife.trade.outbox",
            name = "enabled",
            havingValue = "true")
    public OutboxScheduler outboxScheduler(OutboxPollingService outboxPollingService) {
        return new OutboxScheduler(outboxPollingService);
    }
}
