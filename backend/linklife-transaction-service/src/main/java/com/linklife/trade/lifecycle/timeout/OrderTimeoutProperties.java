package com.linklife.trade.lifecycle.timeout;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.time.ZoneId;

/**
 * 超时订单自动关闭配置，前缀 {@code linklife.trade.order-timeout}。
 *
 * <p>调度器默认关闭（{@code enabled=false}），只有显式设置
 * {@code LINKLIFE_ORDER_TIMEOUT_ENABLED=true} 才允许创建调度任务。</p>
 *
 * <p>所有边界在启动时严格校验（fail-closed），非法值直接抛异常，不做静默修正；
 * {@code payment-timeout} 必须是 1 秒到 24 小时之间的整秒 Duration，以匹配订单创建时刻、
 * MySQL {@code TIMESTAMP} 与 MQ {@code dueAt} 的秒级事实精度。本类不包含密码、连接串或任何环境秘密。</p>
 */
@Component
@ConfigurationProperties(prefix = "linklife.trade.order-timeout")
public class OrderTimeoutProperties {

    private boolean enabled = false;
    private Duration paymentTimeout = Duration.ofMinutes(15);
    private String zoneId = "Asia/Shanghai";
    private long scanDelayMs = 60_000L;
    private long initialDelayMs = 30_000L;
    private int batchSize = 100;
    private int maxBatchesPerRun = 10;

    @PostConstruct
    public void validate() {
        if (paymentTimeout == null || paymentTimeout.compareTo(Duration.ofSeconds(1)) < 0
                || paymentTimeout.compareTo(Duration.ofHours(24)) > 0
                || paymentTimeout.getNano() != 0) {
            throw new IllegalStateException(
                    "linklife.trade.order-timeout.payment-timeout 必须是 1 秒到 24 小时之间的整秒 Duration");
        }
        try {
            ZoneId.of(zoneId);
        } catch (Exception e) {
            throw new IllegalStateException("linklife.trade.order-timeout.zone-id 非法");
        }
        if (scanDelayMs < 1000L || scanDelayMs > 3_600_000L) {
            throw new IllegalStateException(
                    "linklife.trade.order-timeout.scan-delay-ms 必须在 1000 到 3600000 之间");
        }
        if (initialDelayMs < 0L || initialDelayMs > 3_600_000L) {
            throw new IllegalStateException(
                    "linklife.trade.order-timeout.initial-delay-ms 必须在 0 到 3600000 之间");
        }
        if (batchSize < 1 || batchSize > 500) {
            throw new IllegalStateException(
                    "linklife.trade.order-timeout.batch-size 必须在 1 到 500 之间");
        }
        if (maxBatchesPerRun < 1 || maxBatchesPerRun > 100) {
            throw new IllegalStateException(
                    "linklife.trade.order-timeout.max-batches-per-run 必须在 1 到 100 之间");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPaymentTimeout() {
        return paymentTimeout;
    }

    public void setPaymentTimeout(Duration paymentTimeout) {
        this.paymentTimeout = paymentTimeout;
    }

    public ZoneId getZoneId() {
        return ZoneId.of(zoneId);
    }

    public void setZoneId(String zoneId) {
        this.zoneId = zoneId;
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
}
