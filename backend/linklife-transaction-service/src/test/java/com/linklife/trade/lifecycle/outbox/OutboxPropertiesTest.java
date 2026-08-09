package com.linklife.trade.lifecycle.outbox;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OutboxProperties 契约测试：默认值、边界、非法配置 fail-closed、yaml 占位符。
 */
class OutboxPropertiesTest {

    @Test
    void defaultsAreAccurateAndValid() {
        OutboxProperties p = new OutboxProperties();

        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getScanDelayMs()).isEqualTo(5_000L);
        assertThat(p.getInitialDelayMs()).isEqualTo(30_000L);
        assertThat(p.getBatchSize()).isEqualTo(50);
        assertThat(p.getMaxBatchesPerRun()).isEqualTo(10);
        assertThat(p.getLeaseSeconds()).isEqualTo(60);
        assertThat(p.getMaxRetries()).isEqualTo(5);
        assertThat(p.getRetryBaseDelayMs()).isEqualTo(1_000L);
        assertThat(p.getRetryMaxDelayMs()).isEqualTo(60_000L);

        p.validate();
    }

    @Test
    void lowerBoundsAreAccepted() {
        OutboxProperties p = new OutboxProperties();
        p.setScanDelayMs(1000L);
        p.setInitialDelayMs(0L);
        p.setBatchSize(1);
        p.setMaxBatchesPerRun(1);
        p.setLeaseSeconds(5);
        p.setMaxRetries(1);
        p.setRetryBaseDelayMs(1000L);
        p.setRetryMaxDelayMs(1000L);

        p.validate();
    }

    @Test
    void upperBoundsAreAccepted() {
        OutboxProperties p = new OutboxProperties();
        p.setScanDelayMs(3_600_000L);
        p.setInitialDelayMs(3_600_000L);
        p.setBatchSize(500);
        p.setMaxBatchesPerRun(100);
        p.setLeaseSeconds(3600);
        p.setMaxRetries(100);
        p.setRetryBaseDelayMs(3_600_000L);
        p.setRetryMaxDelayMs(86_400_000L);

        p.validate();
    }

    @Test
    void invalidValuesFailClosed() {
        assertInvalid(p -> p.setScanDelayMs(999L));
        assertInvalid(p -> p.setScanDelayMs(3_600_001L));
        assertInvalid(p -> p.setInitialDelayMs(-1L));
        assertInvalid(p -> p.setInitialDelayMs(3_600_001L));
        assertInvalid(p -> p.setBatchSize(0));
        assertInvalid(p -> p.setBatchSize(501));
        assertInvalid(p -> p.setMaxBatchesPerRun(0));
        assertInvalid(p -> p.setMaxBatchesPerRun(101));
        assertInvalid(p -> p.setLeaseSeconds(4));
        assertInvalid(p -> p.setLeaseSeconds(3601));
        assertInvalid(p -> p.setMaxRetries(0));
        assertInvalid(p -> p.setMaxRetries(101));
        assertInvalid(p -> p.setRetryBaseDelayMs(99L));
        assertInvalid(p -> p.setRetryBaseDelayMs(100L));
        assertInvalid(p -> p.setRetryBaseDelayMs(999L));
        assertInvalid(p -> p.setRetryBaseDelayMs(1500L));
        assertInvalid(p -> p.setRetryBaseDelayMs(3_600_001L));
        assertInvalid(p -> p.setRetryMaxDelayMs(86_400_001L));
        assertInvalid(p -> p.setRetryMaxDelayMs(60_500L));
    }

    @Test
    void wholeSecondRetryDelaysAreAccepted() {
        OutboxProperties p = new OutboxProperties();
        p.setRetryBaseDelayMs(1000L);
        p.setRetryMaxDelayMs(60_000L);
        p.validate();

        OutboxProperties q = new OutboxProperties();
        q.setRetryBaseDelayMs(2000L);
        q.setRetryMaxDelayMs(64_000L);
        q.validate();
    }

    @Test
    void retryMaxDelayMustNotBeBelowBaseDelay() {
        OutboxProperties p = new OutboxProperties();
        p.setRetryBaseDelayMs(5000L);
        p.setRetryMaxDelayMs(1000L);

        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void yamlPlaceholdersAndDefaultsContract() throws Exception {
        String yaml = new String(Files.readAllBytes(
                Paths.get("src/main/resources/application.yaml")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");

        assertThat(yaml).contains("linklife:");
        assertThat(yaml).contains("    order-timeout:");
        assertThat(yaml).contains("    outbox:");
        assertThat(yaml).contains("enabled: ${LINKLIFE_OUTBOX_ENABLED:false}");
        assertThat(yaml).contains("scan-delay-ms: ${LINKLIFE_OUTBOX_SCAN_DELAY_MS:5000}");
        assertThat(yaml).contains("initial-delay-ms: ${LINKLIFE_OUTBOX_INITIAL_DELAY_MS:30000}");
        assertThat(yaml).contains("batch-size: ${LINKLIFE_OUTBOX_BATCH_SIZE:50}");
        assertThat(yaml).contains("max-batches-per-run: ${LINKLIFE_OUTBOX_MAX_BATCHES:10}");
        assertThat(yaml).contains("lease-seconds: ${LINKLIFE_OUTBOX_LEASE_SECONDS:60}");
        assertThat(yaml).contains("max-retries: ${LINKLIFE_OUTBOX_MAX_RETRIES:5}");
        assertThat(yaml).contains("retry-base-delay-ms: ${LINKLIFE_OUTBOX_RETRY_BASE_DELAY_MS:1000}");
        assertThat(yaml).contains("retry-max-delay-ms: ${LINKLIFE_OUTBOX_RETRY_MAX_DELAY_MS:60000}");
    }

    private interface PropertyMutator {
        void apply(OutboxProperties properties);
    }

    private void assertInvalid(PropertyMutator mutator) {
        OutboxProperties p = new OutboxProperties();
        mutator.apply(p);
        assertThatThrownBy(p::validate)
                .isInstanceOf(IllegalStateException.class);
    }
}
