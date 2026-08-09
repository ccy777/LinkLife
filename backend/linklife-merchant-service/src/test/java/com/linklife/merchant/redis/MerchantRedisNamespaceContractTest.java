package com.linklife.merchant.redis;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Merchant Redis namespace 契约：cache/lock/geo 全部 merchant:*。
 */
class MerchantRedisNamespaceContractTest {

    @Test
    void allMerchantConstantsUseMerchantPrefix() {
        assertThat(MerchantRedisConstants.CACHE_SHOP_KEY).isEqualTo("merchant:cache:shop:");
        assertThat(MerchantRedisConstants.CACHE_SHOP_TYPE_KEY).isEqualTo("merchant:cache:shop-type:list");
        assertThat(MerchantRedisConstants.LOCK_SHOP_KEY).isEqualTo("merchant:lock:shop:");
        assertThat(MerchantRedisConstants.SHOP_GEO_KEY).isEqualTo("merchant:shop:geo:");
    }

    @Test
    void legacyPrefixesNotUsedByConstants() {
        assertThat(MerchantRedisConstants.CACHE_SHOP_KEY).doesNotStartWith("cache:shop:");
        assertThat(MerchantRedisConstants.LOCK_SHOP_KEY).doesNotStartWith("lock:shop:");
        assertThat(MerchantRedisConstants.SHOP_GEO_KEY).doesNotStartWith("shop:geo:");
    }
}
