package com.linklife.merchant.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.linklife.merchant.entity.Shop;
import com.linklife.merchant.entity.ShopType;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Merchant 进程本地缓存（Caffeine L1）。
 *
 * <ul>
 *   <li>Shop：Cache&lt;Long, Optional&lt;Shop&gt;&gt;；getIfPresent==null → MISS，
 *       Optional.of(shop) → positive hit，Optional.empty() → negative hit；</li>
 *   <li>ShopType：Cache&lt;String, List&lt;ShopType&gt;&gt;；空列表可缓存，返回 immutable copy；</li>
 *   <li>enabled=false → 全部 bypass（lookup 恒 MISS、put/invalidate 空操作），行为退回 Redis-only。</li>
 * </ul>
 *
 * <p>多实例语义：Redis L2 共享；Caffeine L1 进程本地，其他实例最多保留到 L1 TTL 到期，
 * 属于 bounded staleness，不宣称分布式强一致。</p>
 */
@Component
@EnableConfigurationProperties(MerchantLocalCacheProperties.class)
public class MerchantLocalCache {

    private static final String SHOP_TYPE_KEY = "shop-type-list";

    /**
     * Shop 本地 epoch fence 的固定 stripe 数（2 的幂，便于快速取模）。
     * 不同 shopId 映射到固定 stripe，允许碰撞：同 stripe 的更新会保守拒绝另一个在途 fill，
     * 只导致一次不写 L1，不产生错误数据，且避免无界 map。
     */
    static final int SHOP_EPOCH_STRIPES = 256;

    private final boolean enabled;
    private final Cache<Long, Optional<Shop>> shopCache;
    private final Cache<String, List<ShopType>> shopTypeCache;
    private final ShopEpochStripe[] shopEpochStripes;

    public MerchantLocalCache(MerchantLocalCacheProperties properties) {
        properties.validate();
        this.enabled = properties.isEnabled();
        this.shopCache = enabled ? Caffeine.newBuilder()
                .maximumSize(properties.getShopMaximumSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getShopTtlSeconds()))
                .recordStats()
                .build() : null;
        this.shopTypeCache = enabled ? Caffeine.newBuilder()
                .maximumSize(properties.getShopTypeMaximumSize())
                .expireAfterWrite(Duration.ofSeconds(properties.getShopTypeTtlSeconds()))
                .recordStats()
                .build() : null;
        this.shopEpochStripes = new ShopEpochStripe[SHOP_EPOCH_STRIPES];
        for (int i = 0; i < SHOP_EPOCH_STRIPES; i++) {
            shopEpochStripes[i] = new ShopEpochStripe();
        }
    }

    /**
     * 返回 Optional.empty()=L1 MISS；Optional.of(Optional.of(shop))=positive；Optional.of(Optional.empty())=negative。
     */
    public Optional<Optional<Shop>> lookupShop(Long id) {
        if (!enabled || id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(shopCache.getIfPresent(id));
    }

    /**
     * L1 MISS 后、L2/DB 加载开始前记录本次加载的本地 epoch。
     * disabled 模式返回 0（无实际缓存行为）。
     */
    public long snapshotShopEpoch(Long id) {
        if (!enabled || id == null) {
            return 0L;
        }
        ShopEpochStripe stripe = shopEpochStripes[stripeOf(id)];
        synchronized (stripe.lock) {
            return stripe.epoch;
        }
    }

    /**
     * 条件 positive 回填：仅当 stripe epoch 未变化（无并发 invalidate）才写入 L1。
     * 检查与写入在同一短临界区，杜绝 check-then-act 竞态；disabled 模式恒拒绝。
     */
    public boolean putShopIfEpochUnchanged(Shop shop, long expectedEpoch) {
        if (!enabled || shop == null || shop.getId() == null) {
            return false;
        }
        Long id = shop.getId();
        ShopEpochStripe stripe = shopEpochStripes[stripeOf(id)];
        synchronized (stripe.lock) {
            if (stripe.epoch != expectedEpoch) {
                return false;
            }
            shopCache.put(id, Optional.of(shop));
            return true;
        }
    }

    /**
     * 条件 negative 回填：同上，写入 Optional.empty()。
     */
    public boolean putShopMissingIfEpochUnchanged(Long id, long expectedEpoch) {
        if (!enabled || id == null) {
            return false;
        }
        ShopEpochStripe stripe = shopEpochStripes[stripeOf(id)];
        synchronized (stripe.lock) {
            if (stripe.epoch != expectedEpoch) {
                return false;
            }
            shopCache.put(id, Optional.empty());
            return true;
        }
    }

    public void invalidateShop(Long id) {
        if (!enabled || id == null) {
            return;
        }
        ShopEpochStripe stripe = shopEpochStripes[stripeOf(id)];
        synchronized (stripe.lock) {
            // 先推进 epoch，再移除当前 L1：旧 epoch 的在途 fill 之后会被拒绝。
            stripe.epoch++;
            shopCache.invalidate(id);
        }
    }

    static int stripeOf(Long id) {
        return Math.floorMod(id, SHOP_EPOCH_STRIPES);
    }

    private static final class ShopEpochStripe {
        final Object lock = new Object();
        long epoch = 0L;
    }

    public Optional<List<ShopType>> lookupShopTypes() {
        if (!enabled) {
            return Optional.empty();
        }
        List<ShopType> cached = shopTypeCache.getIfPresent(SHOP_TYPE_KEY);
        return cached == null ? Optional.empty() : Optional.of(List.copyOf(cached));
    }

    public void putShopTypes(List<ShopType> types) {
        if (enabled && types != null) {
            shopTypeCache.put(SHOP_TYPE_KEY, List.copyOf(types));
        }
    }

    void invalidateAllForTests() {
        if (enabled) {
            shopCache.invalidateAll();
            shopTypeCache.invalidateAll();
        }
    }

    CacheStats shopStats() {
        return enabled ? shopCache.stats() : CacheStats.empty();
    }

    CacheStats shopTypeStats() {
        return enabled ? shopTypeCache.stats() : CacheStats.empty();
    }
}
