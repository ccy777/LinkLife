package com.linklife.trade.lifecycle.timeout;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OrderTimeoutProperties 单元测试：默认值、上下界、非法值启动校验 fail-closed、
 * 环境变量占位符与属性名契约。纯 Java，不启动 Spring 上下文。
 */
class OrderTimeoutPropertiesTest {

    @Test
    void defaultsAreAccurateAndValid() {
        OrderTimeoutProperties p = new OrderTimeoutProperties();

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getUnpaidTimeoutMinutes()).isEqualTo(15);
        assertThat(p.getScanDelayMs()).isEqualTo(60_000L);
        assertThat(p.getInitialDelayMs()).isEqualTo(30_000L);
        assertThat(p.getBatchSize()).isEqualTo(100);
        assertThat(p.getMaxBatchesPerRun()).isEqualTo(10);

        p.validate();
    }

    @Test
    void enabledDefaultsToFalse() {
        assertThat(new OrderTimeoutProperties().isEnabled()).isFalse();
    }

    @Test
    void lowerBoundsAreAccepted() {
        OrderTimeoutProperties p = new OrderTimeoutProperties();
        p.setUnpaidTimeoutMinutes(1);
        p.setScanDelayMs(1000L);
        p.setInitialDelayMs(0L);
        p.setBatchSize(1);
        p.setMaxBatchesPerRun(1);

        p.validate();
    }

    @Test
    void upperBoundsAreAccepted() {
        OrderTimeoutProperties p = new OrderTimeoutProperties();
        p.setUnpaidTimeoutMinutes(1440);
        p.setScanDelayMs(3_600_000L);
        p.setInitialDelayMs(3_600_000L);
        p.setBatchSize(500);
        p.setMaxBatchesPerRun(100);

        p.validate();
    }

    @Test
    void invalidValuesFailClosedAtStartup() {
        assertInvalid(props -> props.setUnpaidTimeoutMinutes(0));
        assertInvalid(props -> props.setUnpaidTimeoutMinutes(1441));
        assertInvalid(props -> props.setScanDelayMs(999L));
        assertInvalid(props -> props.setScanDelayMs(3_600_001L));
        assertInvalid(props -> props.setInitialDelayMs(-1L));
        assertInvalid(props -> props.setInitialDelayMs(3_600_001L));
        assertInvalid(props -> props.setBatchSize(0));
        assertInvalid(props -> props.setBatchSize(501));
        assertInvalid(props -> props.setMaxBatchesPerRun(0));
        assertInvalid(props -> props.setMaxBatchesPerRun(101));
    }

    @Test
    void yamlPlaceholdersAndPropertyNamesContract() throws Exception {
        String yaml = new String(Files.readAllBytes(
                Paths.get("src/main/resources/application.yaml")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        assertThat(yaml).contains("linklife:");
        assertThat(yaml).contains("  trade:\n    order-timeout:");
        assertThat(yaml).contains("enabled: ${LINKLIFE_ORDER_TIMEOUT_ENABLED:false}");
        assertThat(yaml).contains("unpaid-timeout-minutes: ${LINKLIFE_ORDER_TIMEOUT_MINUTES:15}");
        assertThat(yaml).contains("scan-delay-ms: ${LINKLIFE_ORDER_TIMEOUT_SCAN_DELAY_MS:60000}");
        assertThat(yaml).contains("initial-delay-ms: ${LINKLIFE_ORDER_TIMEOUT_INITIAL_DELAY_MS:30000}");
        assertThat(yaml).contains("batch-size: ${LINKLIFE_ORDER_TIMEOUT_BATCH_SIZE:100}");
        assertThat(yaml).contains("max-batches-per-run: ${LINKLIFE_ORDER_TIMEOUT_MAX_BATCHES:10}");
    }

    private interface PropertyMutator {
        void apply(OrderTimeoutProperties properties);
    }

    private void assertInvalid(PropertyMutator mutator) {
        OrderTimeoutProperties p = new OrderTimeoutProperties();
        mutator.apply(p);
        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class);
    }
}
