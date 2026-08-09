package com.linklife.merchant.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Merchant 本地缓存（Caffeine L1）配置。
 *
 * <p>默认值为 Stage 5B dev 初始值，不是生产容量结论；
 * enabled=false 时 L1 完全 bypass，行为退回 Redis L2 + MySQL（供 Stage 6 A/B 使用）。</p>
 */
@ConfigurationProperties(prefix = "linklife.cache.local")
public class MerchantLocalCacheProperties {

    private boolean enabled = true;
    private int shopMaximumSize = 10000;
    private long shopTtlSeconds = 10;
    private int shopTypeMaximumSize = 16;
    private long shopTypeTtlSeconds = 60;

    /**
     * enabled=true 时 maximum-size / TTL 必须为正，否则启动失败（fail-fast）。
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (shopMaximumSize <= 0 || shopTypeMaximumSize <= 0
                || shopTtlSeconds <= 0 || shopTypeTtlSeconds <= 0) {
            throw new IllegalStateException(
                    "linklife.cache.local 启用的 maximum-size 与 TTL 必须为正: "
                            + "shop-maximum-size=" + shopMaximumSize
                            + ", shop-ttl-seconds=" + shopTtlSeconds
                            + ", shop-type-maximum-size=" + shopTypeMaximumSize
                            + ", shop-type-ttl-seconds=" + shopTypeTtlSeconds);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getShopMaximumSize() {
        return shopMaximumSize;
    }

    public void setShopMaximumSize(int shopMaximumSize) {
        this.shopMaximumSize = shopMaximumSize;
    }

    public long getShopTtlSeconds() {
        return shopTtlSeconds;
    }

    public void setShopTtlSeconds(long shopTtlSeconds) {
        this.shopTtlSeconds = shopTtlSeconds;
    }

    public int getShopTypeMaximumSize() {
        return shopTypeMaximumSize;
    }

    public void setShopTypeMaximumSize(int shopTypeMaximumSize) {
        this.shopTypeMaximumSize = shopTypeMaximumSize;
    }

    public long getShopTypeTtlSeconds() {
        return shopTypeTtlSeconds;
    }

    public void setShopTypeTtlSeconds(long shopTypeTtlSeconds) {
        this.shopTypeTtlSeconds = shopTypeTtlSeconds;
    }
}
