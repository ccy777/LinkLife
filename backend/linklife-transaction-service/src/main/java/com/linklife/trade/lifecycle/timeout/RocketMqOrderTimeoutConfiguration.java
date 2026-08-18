package com.linklife.trade.lifecycle.timeout;

import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RocketMQ 5.x gRPC provider 配置；客户端由可恢复生命周期管理器后台建立。 */
@Configuration
@ConditionalOnProperty(
        prefix = "linklife.trade.order-timeout.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqOrderTimeoutConfiguration {

    @Bean
    public ClientServiceProvider rocketMqClientServiceProvider() {
        return ClientServiceProvider.loadService();
    }

}
