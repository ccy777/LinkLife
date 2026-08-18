package com.linklife.trade.lifecycle.timeout;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTimeoutRocketMqPropertiesTest {

    @Test
    void disabledRequiresNoBrokerAndDefaultsAreSafe() {
        OrderTimeoutRocketMqProperties properties = new OrderTimeoutRocketMqProperties();
        properties.validate();
        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getEndpoints()).isNull();
        assertThat(properties.getRequestTimeout()).isEqualTo(Duration.ofSeconds(3));
    }

    @Test
    void enabledRequiresEndpointTopicTagAndConsumerGroup() {
        OrderTimeoutRocketMqProperties properties = new OrderTimeoutRocketMqProperties();
        properties.setEnabled(true);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("endpoints");

        properties.setEndpoints("127.0.0.1:8081");
        properties.validate();
        properties.setTopic(" ");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topic");
    }

    @Test
    void rangeViolationsFailClosed() {
        OrderTimeoutRocketMqProperties properties = new OrderTimeoutRocketMqProperties();
        properties.setRequestTimeout(Duration.ofMillis(999));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setRequestTimeout(Duration.ofSeconds(3));
        properties.setConsumptionThreadCount(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
