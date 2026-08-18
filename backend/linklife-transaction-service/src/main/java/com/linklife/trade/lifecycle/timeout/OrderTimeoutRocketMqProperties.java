package com.linklife.trade.lifecycle.timeout;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** RocketMQ timeout trigger 配置；disabled 时不要求 Broker endpoint。 */
@Component
@ConfigurationProperties(prefix = "linklife.trade.order-timeout.rocketmq")
public class OrderTimeoutRocketMqProperties {

    private boolean enabled;
    private String endpoints;
    private String topic = "linklife-order-payment-timeout";
    private String tag = "PAYMENT_TIMEOUT_CHECK";
    private String consumerGroup = "linklife-transaction-order-timeout-v1";
    private boolean sslEnabled = true;
    private Duration requestTimeout = Duration.ofSeconds(3);
    private int consumptionThreadCount = 4;

    @PostConstruct
    public void validate() {
        if (requestTimeout == null || requestTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || requestTimeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalStateException(
                    "linklife.trade.order-timeout.rocketmq.request-timeout 必须在 1 到 30 秒之间");
        }
        if (consumptionThreadCount < 1 || consumptionThreadCount > 64) {
            throw new IllegalStateException(
                    "linklife.trade.order-timeout.rocketmq.consumption-thread-count 必须在 1 到 64 之间");
        }
        if (enabled) {
            require(endpoints, "linklife.trade.order-timeout.rocketmq.endpoints");
            require(topic, "linklife.trade.order-timeout.rocketmq.topic");
            require(tag, "linklife.trade.order-timeout.rocketmq.tag");
            require(consumerGroup, "linklife.trade.order-timeout.rocketmq.consumer-group");
        }
    }

    private void require(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " 不能为空");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getEndpoints() { return endpoints; }
    public void setEndpoints(String endpoints) { this.endpoints = endpoints; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }
    public String getConsumerGroup() { return consumerGroup; }
    public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
    public boolean isSslEnabled() { return sslEnabled; }
    public void setSslEnabled(boolean sslEnabled) { this.sslEnabled = sslEnabled; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration requestTimeout) { this.requestTimeout = requestTimeout; }
    public int getConsumptionThreadCount() { return consumptionThreadCount; }
    public void setConsumptionThreadCount(int consumptionThreadCount) {
        this.consumptionThreadCount = consumptionThreadCount;
    }
}
