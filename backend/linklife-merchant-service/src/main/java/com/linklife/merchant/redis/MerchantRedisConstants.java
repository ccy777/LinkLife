package com.linklife.merchant.redis;

/**
 * Merchant Redis namespace 契约（DB 0，全部 merchant:*）。
 */
public class MerchantRedisConstants {

    public static final Long CACHE_NULL_TTL = 2L;
    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "merchant:cache:shop:";
    public static final String CACHE_SHOP_TYPE_KEY = "merchant:cache:shop-type:list";
    public static final Long CACHE_SHOP_TYPE_TTL = 30L;
    public static final String LOCK_SHOP_KEY = "merchant:lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;
    public static final long CACHE_TTL_JITTER_SECONDS = 3L;
    public static final int CACHE_MUTEX_MAX_ATTEMPTS = 5;
    public static final long CACHE_MUTEX_BASE_WAIT_MILLIS = 50L;
    public static final long CACHE_MUTEX_MAX_RETRY_WAIT_MILLIS = 200L;
    public static final String SHOP_GEO_KEY = "merchant:shop:geo:";

    private MerchantRedisConstants() {
    }
}
