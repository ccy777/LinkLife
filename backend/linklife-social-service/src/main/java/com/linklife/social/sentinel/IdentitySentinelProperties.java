package com.linklife.social.sentinel;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Social → Identity 熔断配置（Stage 5A 开发默认值，不是生产容量结论）。
 */
@ConfigurationProperties(prefix = "linklife.sentinel.identity")
public class IdentitySentinelProperties {

    private boolean enabled = true;
    private double exceptionRatio = 0.5;
    private int minimumRequestAmount = 5;
    private int statIntervalMs = 10000;
    private int timeWindowSeconds = 5;

    /**
     * enabled=true 时参数越界直接启动失败（fail-fast）。
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (exceptionRatio <= 0 || exceptionRatio > 1) {
            throw new IllegalStateException(
                    "linklife.sentinel.identity.exception-ratio 必须满足 0 < ratio <= 1: " + exceptionRatio);
        }
        if (minimumRequestAmount < 1) {
            throw new IllegalStateException(
                    "linklife.sentinel.identity.minimum-request-amount 必须 >= 1: " + minimumRequestAmount);
        }
        if (statIntervalMs <= 0) {
            throw new IllegalStateException(
                    "linklife.sentinel.identity.stat-interval-ms 必须 > 0: " + statIntervalMs);
        }
        if (timeWindowSeconds < 1) {
            throw new IllegalStateException(
                    "linklife.sentinel.identity.time-window-seconds 必须 >= 1: " + timeWindowSeconds);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getExceptionRatio() {
        return exceptionRatio;
    }

    public void setExceptionRatio(double exceptionRatio) {
        this.exceptionRatio = exceptionRatio;
    }

    public int getMinimumRequestAmount() {
        return minimumRequestAmount;
    }

    public void setMinimumRequestAmount(int minimumRequestAmount) {
        this.minimumRequestAmount = minimumRequestAmount;
    }

    public int getStatIntervalMs() {
        return statIntervalMs;
    }

    public void setStatIntervalMs(int statIntervalMs) {
        this.statIntervalMs = statIntervalMs;
    }

    public int getTimeWindowSeconds() {
        return timeWindowSeconds;
    }

    public void setTimeWindowSeconds(int timeWindowSeconds) {
        this.timeWindowSeconds = timeWindowSeconds;
    }
}
