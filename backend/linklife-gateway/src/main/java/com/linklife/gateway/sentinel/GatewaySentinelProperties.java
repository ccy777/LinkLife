package com.linklife.gateway.sentinel;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Gateway 三类精确热点 API 的 Sentinel 流控配置。
 *
 * <p>默认值仅表示 Stage 5A 开发初始保护值，不是系统容量/生产阈值；
 * Stage 6 真实压测后重新标定。</p>
 */
@ConfigurationProperties(prefix = "linklife.sentinel.gateway")
public class GatewaySentinelProperties {

    private boolean enabled = true;
    private double hotBlogQps = 100;
    private double shopOfTypeQps = 100;
    private double seckillQps = 50;

    /**
     * enabled=true 时任一 QPS 必须为正数，否则启动失败（避免规则静默失效）。
     */
    public void validate() {
        if (!enabled) {
            return;
        }
        if (hotBlogQps <= 0 || shopOfTypeQps <= 0 || seckillQps <= 0) {
            throw new IllegalStateException(
                    "linklife.sentinel.gateway 启用的 QPS 必须为正数: hot-blog-qps="
                            + hotBlogQps + ", shop-of-type-qps=" + shopOfTypeQps
                            + ", seckill-qps=" + seckillQps);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getHotBlogQps() {
        return hotBlogQps;
    }

    public void setHotBlogQps(double hotBlogQps) {
        this.hotBlogQps = hotBlogQps;
    }

    public double getShopOfTypeQps() {
        return shopOfTypeQps;
    }

    public void setShopOfTypeQps(double shopOfTypeQps) {
        this.shopOfTypeQps = shopOfTypeQps;
    }

    public double getSeckillQps() {
        return seckillQps;
    }

    public void setSeckillQps(double seckillQps) {
        this.seckillQps = seckillQps;
    }
}
