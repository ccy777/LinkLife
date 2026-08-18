package com.linklife.transaction.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TransactionProductionConfigurationValidatorTest {

    private final TransactionProductionConfigurationValidator validator =
            new TransactionProductionConfigurationValidator();

    @Test
    void mqDisabledDoesNotRequireBrokerConfiguration() {
        assertThatCode(() -> validator.validate(base()))
                .doesNotThrowAnyException();
    }

    @Test
    void mqEnabledRequiresSchedulerOutboxAndEndpointContract() {
        MockEnvironment missingScheduler = base()
                .withProperty("linklife.trade.order-timeout.rocketmq.enabled", "true")
                .withProperty("linklife.trade.outbox.enabled", "true")
                .withProperty("linklife.trade.order-timeout.rocketmq.endpoints", "127.0.0.1:8081")
                .withProperty("linklife.trade.order-timeout.rocketmq.topic", "timeout")
                .withProperty("linklife.trade.order-timeout.rocketmq.tag", "TIMEOUT")
                .withProperty("linklife.trade.order-timeout.rocketmq.consumer-group", "timeout-v1")
                .withProperty("linklife.trade.order-timeout.rocketmq.request-timeout", "3s");
        assertThatThrownBy(() -> validator.validate(missingScheduler))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("linklife.trade.order-timeout.enabled");

        MockEnvironment missingEndpoint = enabled();
        missingEndpoint.setProperty("linklife.trade.order-timeout.rocketmq.endpoints", " ");
        assertThatThrownBy(() -> validator.validate(missingEndpoint))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("linklife.trade.order-timeout.rocketmq.endpoints");

        MockEnvironment invalidTimeout = enabled();
        invalidTimeout.setProperty("linklife.trade.order-timeout.rocketmq.request-timeout", "0s");
        assertThatThrownBy(() -> validator.validate(invalidTimeout))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("linklife.trade.order-timeout.rocketmq.request-timeout");
    }

    @Test
    void validMqConfigurationPassesCommonGuard() {
        assertThatCode(() -> validator.validate(enabled()))
                .doesNotThrowAnyException();
    }

    private MockEnvironment base() {
        return new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1/linklife_test")
                .withProperty("spring.data.redis.host", "127.0.0.1")
                .withProperty("spring.data.redis.port", "6379")
                .withProperty("spring.data.redis.database", "1")
                .withProperty("linklife.runtime.production-validation-enabled", "false")
                .withProperty("linklife.trade.order-timeout.enabled", "false")
                .withProperty("linklife.trade.order-timeout.rocketmq.enabled", "false")
                .withProperty("linklife.trade.outbox.enabled", "false");
    }

    private MockEnvironment enabled() {
        return base()
                .withProperty("linklife.trade.order-timeout.enabled", "true")
                .withProperty("linklife.trade.order-timeout.rocketmq.enabled", "true")
                .withProperty("linklife.trade.outbox.enabled", "true")
                .withProperty("linklife.trade.order-timeout.rocketmq.endpoints", "127.0.0.1:8081")
                .withProperty("linklife.trade.order-timeout.rocketmq.topic", "timeout")
                .withProperty("linklife.trade.order-timeout.rocketmq.tag", "TIMEOUT")
                .withProperty("linklife.trade.order-timeout.rocketmq.consumer-group", "timeout-v1")
                .withProperty("linklife.trade.order-timeout.rocketmq.request-timeout", "3s");
    }
}
