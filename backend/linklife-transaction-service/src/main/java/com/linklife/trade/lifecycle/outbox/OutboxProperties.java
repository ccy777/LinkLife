package com.linklife.trade.lifecycle.outbox;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

/**
 * 本地 Outbox 轮询配置，前缀 {@code linklife.trade.outbox}，默认关闭。
 *
 * <p>所有边界在启动时严格校验（fail-closed），非法值抛异常，不做静默修正；
 * 本类不包含密码、连接串或任何环境秘密。</p>
 */
@Component
@ConfigurationProperties(prefix = "linklife.trade.outbox")
public class OutboxProperties {

    private boolean enabled = false;
    private long scanDelayMs = 5_000L;
    private long initialDelayMs = 30_000L;
    private int batchSize = 50;
    private int maxBatchesPerRun = 10;
    private int leaseSeconds = 60;
    private int maxRetries = 5;
    private long retryBaseDelayMs = 1_000L;
    private long retryMaxDelayMs = 60_000L;

    @PostConstruct
    public void validate() {
        if (scanDelayMs < 1000L || scanDelayMs > 3_600_000L) {
            throw new IllegalStateException("linklife.trade.outbox.scan-delay-ms 必须在 1000 到 3600000 之间");
        }
        if (initialDelayMs < 0L || initialDelayMs > 3_600_000L) {
            throw new IllegalStateException("linklife.trade.outbox.initial-delay-ms 必须在 0 到 3600000 之间");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalStateException("linklife.trade.outbox.batch-size 必须在 1 到 500 之间");
        }
        if (maxBatchesPerRun < 1 || maxBatchesPerRun > 100) {
            throw new IllegalStateException("linklife.trade.outbox.max-batches-per-run 必须在 1 到 100 之间");
        }
        if (leaseSeconds < 5 || leaseSeconds > 3600) {
            throw new IllegalStateException("linklife.trade.outbox.lease-seconds 必须在 5 到 3600 之间");
        }
        if (maxRetries < 1 || maxRetries > 100) {
            throw new IllegalStateException("linklife.trade.outbox.max-retries 必须在 1 到 100 之间");
        }
        if (retryBaseDelayMs < 1000L || retryBaseDelayMs > 3_600_000L
                || retryBaseDelayMs % 1000L != 0) {
            throw new IllegalStateException(
                    "linklife.trade.outbox.retry-base-delay-ms 必须在 1000 到 3600000 之间且为 1000 的整数倍");
        }
        if (retryMaxDelayMs < retryBaseDelayMs || retryMaxDelayMs > 86_400_000L
                || retryMaxDelayMs % 1000L != 0) {
            throw new IllegalStateException(
                    "linklife.trade.outbox.retry-max-delay-ms 必须不小于 retry-base-delay-ms、"
                            + "不超过 86400000 且为 1000 的整数倍");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getScanDelayMs() {
        return scanDelayMs;
    }

    public void setScanDelayMs(long scanDelayMs) {
        this.scanDelayMs = scanDelayMs;
    }

    public long getInitialDelayMs() {
        return initialDelayMs;
    }

    public void setInitialDelayMs(long initialDelayMs) {
        this.initialDelayMs = initialDelayMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxBatchesPerRun() {
        return maxBatchesPerRun;
    }

    public void setMaxBatchesPerRun(int maxBatchesPerRun) {
        this.maxBatchesPerRun = maxBatchesPerRun;
    }

    public int getLeaseSeconds() {
        return leaseSeconds;
    }

    public void setLeaseSeconds(int leaseSeconds) {
        this.leaseSeconds = leaseSeconds;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryBaseDelayMs() {
        return retryBaseDelayMs;
    }

    public void setRetryBaseDelayMs(long retryBaseDelayMs) {
        this.retryBaseDelayMs = retryBaseDelayMs;
    }

    public long getRetryMaxDelayMs() {
        return retryMaxDelayMs;
    }

    public void setRetryMaxDelayMs(long retryMaxDelayMs) {
        this.retryMaxDelayMs = retryMaxDelayMs;
    }
}
