package com.linklife.merchant.cache;

import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.linklife.merchant.entity.Shop;
import com.linklife.merchant.entity.ShopType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MerchantLocalCache（Stage5B-R2）：本地 stripe epoch fence。
 * positive/negative 回填必须携带 snapshot 时的 epoch，invalidate 先推进 epoch 再清 L1；
 * 旧 epoch 的 in-flight fill 被拒绝，新 epoch fill 正常，disabled 无缓存行为。
 */
class MerchantLocalCacheTest {

    private static MerchantLocalCacheProperties props() {
        MerchantLocalCacheProperties p = new MerchantLocalCacheProperties();
        p.setEnabled(true);
        p.setShopMaximumSize(100);
        p.setShopTtlSeconds(10);
        p.setShopTypeMaximumSize(16);
        p.setShopTypeTtlSeconds(60);
        return p;
    }

    private static Shop shop(long id, String name) {
        Shop s = new Shop();
        s.setId(id);
        s.setName(name);
        return s;
    }

    @Test
    void positivePutGetRoundTrip() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        long epoch = cache.snapshotShopEpoch(1L);
        assertThat(cache.putShopIfEpochUnchanged(shop(1L, "demo"), epoch)).isTrue();

        Optional<Optional<Shop>> got = cache.lookupShop(1L);

        assertThat(got).isPresent();
        assertThat(got.get()).isPresent();
        assertThat(got.get().get().getName()).isEqualTo("demo");
    }

    @Test
    void negativePutGetIsDistinguishedFromMiss() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        assertThat(cache.lookupShop(1L)).isEmpty();

        long epoch = cache.snapshotShopEpoch(1L);
        assertThat(cache.putShopMissingIfEpochUnchanged(1L, epoch)).isTrue();

        Optional<Optional<Shop>> got = cache.lookupShop(1L);
        assertThat(got).isPresent();
        assertThat(got.get()).isEmpty();
    }

    @Test
    void invalidateRemovesPositiveAndNegative() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        long ePos = cache.snapshotShopEpoch(1L);
        cache.putShopIfEpochUnchanged(shop(1L, "demo"), ePos);
        long eNeg = cache.snapshotShopEpoch(2L);
        cache.putShopMissingIfEpochUnchanged(2L, eNeg);

        cache.invalidateShop(1L);
        cache.invalidateShop(2L);

        assertThat(cache.lookupShop(1L)).isEmpty();
        assertThat(cache.lookupShop(2L)).isEmpty();
    }

    @Test
    void shopTypeListIsDefensivelyCopied() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        ShopType a = new ShopType();
        a.setId(1L);
        a.setName("A");
        List<ShopType> source = new ArrayList<>(List.of(a));
        cache.putShopTypes(source);

        List<ShopType> got = cache.lookupShopTypes().orElseThrow();
        assertThatThrownBy(() -> got.add(new ShopType()))
                .isInstanceOf(UnsupportedOperationException.class);

        source.add(new ShopType());
        assertThat(cache.lookupShopTypes().orElseThrow()).hasSize(1);
    }

    @Test
    void emptyShopTypeListIsCachedAsPositive() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        cache.putShopTypes(List.of());

        Optional<List<ShopType>> got = cache.lookupShopTypes();

        assertThat(got).isPresent();
        assertThat(got.get()).isEmpty();
    }

    @Test
    void statsRecordHitAndMiss() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        long epoch = cache.snapshotShopEpoch(1L);
        cache.putShopIfEpochUnchanged(shop(1L, "demo"), epoch);
        cache.lookupShop(1L);
        cache.lookupShop(99L);

        CacheStats stats = cache.shopStats();
        assertThat(stats.hitCount()).isEqualTo(1);
        assertThat(stats.missCount()).isEqualTo(1);
    }

    @Test
    void ttlExpiryReturnsMiss() throws Exception {
        MerchantLocalCacheProperties p = props();
        p.setShopTtlSeconds(1);
        p.setShopTypeTtlSeconds(1);
        MerchantLocalCache cache = new MerchantLocalCache(p);
        long epoch = cache.snapshotShopEpoch(1L);
        cache.putShopIfEpochUnchanged(shop(1L, "demo"), epoch);
        assertThat(cache.lookupShop(1L)).isPresent();

        Thread.sleep(1100);

        assertThat(cache.lookupShop(1L)).isEmpty();
    }

    @Test
    void stalePositiveFillIsRejectedAfterInvalidate() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        long staleEpoch = cache.snapshotShopEpoch(1L);
        cache.invalidateShop(1L); // epoch 前进

        assertThat(cache.putShopIfEpochUnchanged(shop(1L, "old"), staleEpoch)).isFalse();
        assertThat(cache.lookupShop(1L)).isEmpty();
    }

    @Test
    void staleNegativeFillIsRejectedAfterInvalidate() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        long staleEpoch = cache.snapshotShopEpoch(1L);
        cache.invalidateShop(1L); // epoch 前进

        assertThat(cache.putShopMissingIfEpochUnchanged(1L, staleEpoch)).isFalse();
        assertThat(cache.lookupShop(1L)).isEmpty();
    }

    @Test
    void sameEpochConditionalFillSucceeds() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        long epoch = cache.snapshotShopEpoch(1L);
        assertThat(cache.putShopIfEpochUnchanged(shop(1L, "v1"), epoch)).isTrue();
        assertThat(cache.lookupShop(1L).get().get().getName()).isEqualTo("v1");

        long negEpoch = cache.snapshotShopEpoch(2L);
        assertThat(cache.putShopMissingIfEpochUnchanged(2L, negEpoch)).isTrue();
        assertThat(cache.lookupShop(2L).get()).isEmpty();
    }

    @Test
    void newEpochFillSucceedsAfterInvalidate() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        long e0 = cache.snapshotShopEpoch(1L);
        cache.putShopIfEpochUnchanged(shop(1L, "old"), e0);
        cache.invalidateShop(1L);

        long e1 = cache.snapshotShopEpoch(1L);
        assertThat(e1).isGreaterThan(e0);
        assertThat(cache.putShopIfEpochUnchanged(shop(1L, "new"), e1)).isTrue();
        assertThat(cache.lookupShop(1L).get().get().getName()).isEqualTo("new");
    }

    @Test
    void stripeCollisionRejectsInflightFillWithoutDeletingOtherEntry() {
        MerchantLocalCache cache = new MerchantLocalCache(props());
        long idA = 1L;
        long idB = idA + MerchantLocalCache.SHOP_EPOCH_STRIPES; // 同 stripe
        assertThat(MerchantLocalCache.stripeOf(idA)).isEqualTo(MerchantLocalCache.stripeOf(idB));

        long epochB = cache.snapshotShopEpoch(idB);
        assertThat(cache.putShopIfEpochUnchanged(shop(idB, "B"), epochB)).isTrue();

        long staleEpochA = cache.snapshotShopEpoch(idA);
        assertThat(staleEpochA).isEqualTo(epochB); // 同 stripe 共享 epoch
        cache.invalidateShop(idA); // 同 stripe epoch++

        // A 的在途 fill 被保守拒绝
        assertThat(cache.putShopIfEpochUnchanged(shop(idA, "staleA"), staleEpochA)).isFalse();
        // 不删除/不污染同 stripe 其它 shop 已有 entry
        assertThat(cache.lookupShop(idB).get().get().getName()).isEqualTo("B");
    }

    @Test
    void disabledModeHasNoCachingBehavior() {
        MerchantLocalCacheProperties p = props();
        p.setEnabled(false);
        MerchantLocalCache cache = new MerchantLocalCache(p);

        long epoch = cache.snapshotShopEpoch(1L);
        assertThat(cache.putShopIfEpochUnchanged(shop(1L, "demo"), epoch)).isFalse();
        assertThat(cache.putShopMissingIfEpochUnchanged(1L, epoch)).isFalse();
        cache.invalidateShop(1L);
        cache.putShopTypes(List.of());

        assertThat(cache.lookupShop(1L)).isEmpty();
        assertThat(cache.lookupShopTypes()).isEmpty();
        assertThat(cache.shopStats().hitCount()).isZero();
        assertThat(cache.shopTypeStats().missCount()).isZero();
    }
}
