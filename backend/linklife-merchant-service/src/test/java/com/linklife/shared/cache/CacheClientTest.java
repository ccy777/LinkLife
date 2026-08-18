package com.linklife.shared.cache;

import com.linklife.merchant.entity.Shop;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.merchant.redis.MerchantRedisConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentCaptor.forClass;
import org.mockito.ArgumentCaptor;

import java.lang.annotation.Annotation;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.RedisSystemException;
import io.lettuce.core.RedisCommandExecutionException;

/**
 * CacheClient 缓存互斥、逻辑过期与 TTL 抖动单元测试。不连接真实 Redis/MySQL。
 */
class CacheClientTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private Executor executor;
    private CacheClient client;
    private CacheClient spy;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        executor = mock(Executor.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        client = new CacheClient(redisTemplate, executor);
        spy = spy(client);
    }

    private Shop shop(long id) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setName("shop" + id);
        return shop;
    }

    private String logicalJson(Shop shop, LocalDateTime expireTime) {
        RedisData data = new RedisData();
        data.setData(shop);
        data.setExpireTime(expireTime);
        return cn.hutool.json.JSONUtil.toJsonStr(data);
    }

    private String expiredJson() {
        return logicalJson(shop(1L), LocalDateTime.now().minusMinutes(1));
    }

    private String freshJson() {
        return logicalJson(shop(1L), LocalDateTime.now().plusMinutes(5));
    }

    @Test
    void newLockOwnerProducesUniqueOwners() {
        String a = client.newLockOwner();
        String b = client.newLockOwner();

        assertThat(a).isNotEqualTo(b);
        assertThat(a).endsWith("-" + Thread.currentThread().getId());
    }

    @Test
    void tryLockUsesOwnerAndTtl() {
        when(valueOps.get(anyString())).thenReturn(null);
        ArgumentCaptor<String> ownerCaptor = forClass(String.class);
        when(valueOps.setIfAbsent(eq("merchant:lock:shop:1"), ownerCaptor.capture(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        Shop result = client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(ownerCaptor.getValue()).isNotEqualTo("1").contains("-");
    }

    @Test
    void setIfAbsentNullFailsClosed() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(null);

        assertThatThrownBy(() -> client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缓存服务暂时不可用");

        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    void unlockLuaFailureDoesNotDirectDelete() {
        doThrow(new RuntimeException("redis down"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any());

        assertThatCode(() -> client.unlockByOwner("merchant:lock:shop:1", "owner-1")).doesNotThrowAnyException();

        verify(redisTemplate, never()).delete("merchant:lock:shop:1");
    }

    @Test
    void unlockFinallyDoesNotOverrideBusinessResult() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("unlock error"))
                .when(redisTemplate).execute(any(RedisScript.class), anyList(), any());

        Shop result = client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void cacheHitDoesNotQueryDb() {
        when(valueOps.get("merchant:cache:shop:1")).thenReturn("{\"id\":1,\"name\":\"shop1\"}");
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(1L);
                }, 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(dbCalls.get()).isZero();
    }

    @Test
    void nullCacheHitReturnsNull() {
        when(valueOps.get(anyString())).thenReturn("");
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(1L);
                }, 30L, TimeUnit.MINUTES);

        assertThat(result).isNull();
        assertThat(dbCalls.get()).isZero();
    }

    @Test
    void doubleCheckAfterLockSkipsDbWhenCacheAppeared() {
        // 旧双 GET 缺陷序列：初始 GET = null（MISS），获锁后下一次 GET = 对象（HIT）
        when(valueOps.get(anyString())).thenReturn(null, "{\"id\":1,\"name\":\"shop1\"}");
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(1L);
                }, 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(dbCalls.get()).isZero();
        verify(valueOps, times(2)).get(anyString());
    }

    @Test
    void cacheHitPerformsSingleGet() {
        when(valueOps.get("merchant:cache:shop:1")).thenReturn("{\"id\":1,\"name\":\"shop1\"}");

        Shop result = client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        verify(valueOps, times(1)).get("merchant:cache:shop:1");
    }

    @Test
    void nullHitPerformsSingleGet() {
        when(valueOps.get("merchant:cache:shop:1")).thenReturn("");

        Shop result = client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result).isNull();
        verify(valueOps, times(1)).get("merchant:cache:shop:1");
    }

    @Test
    void waitRecheckUsesSingleSnapshot() throws Exception {
        // 初始 MISS；等待后下一次 GET 为对象 → 直接返回，不查 DB
        when(valueOps.get(anyString())).thenReturn(null, "{\"id\":1,\"name\":\"shop1\"}");
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        doNothing().when(spy).sleepBeforeRetry(anyLong());
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = spy.queryWithMutex("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(1L);
                }, 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(dbCalls.get()).isZero();
        verify(valueOps, times(2)).get(anyString());
    }

    @Test
    void finalRecheckHitReturnsObject() throws Exception {
        // 第 5 次锁失败后最终复查命中对象 → 返回对象
        when(valueOps.get(anyString()))
                .thenReturn(null, null, null, null, null, "{\"id\":1,\"name\":\"shop1\"}");
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        doNothing().when(spy).sleepBeforeRetry(anyLong());
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = spy.queryWithMutex("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(1L);
                }, 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(dbCalls.get()).isZero();
    }

    @Test
    void finalRecheckNullHitReturnsNull() throws Exception {
        when(valueOps.get(anyString()))
                .thenReturn(null, null, null, null, null, "");
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        doNothing().when(spy).sleepBeforeRetry(anyLong());
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = spy.queryWithMutex("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(1L);
                }, 30L, TimeUnit.MINUTES);

        assertThat(result).isNull();
        assertThat(dbCalls.get()).isZero();
    }

    @Test
    void finalRecheckStillMissThrowsBusy() throws Exception {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        doNothing().when(spy).sleepBeforeRetry(anyLong());
        AtomicInteger dbCalls = new AtomicInteger();

        assertThatThrownBy(() -> spy.queryWithMutex("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(1L);
                }, 30L, TimeUnit.MINUTES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缓存重建繁忙");

        assertThat(dbCalls.get()).isZero();
    }

    @Test
    void needRebuildGetExceptionReturnsOldAndReleasesLock() {
        when(valueOps.get(anyString())).thenReturn(expiredJson())
                .thenThrow(new RedisSystemException("Error in execution",
                        new RedisCommandExecutionException("REDIS DOWN")));
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        Shop result = client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList("merchant:lock:shop:1")), any());
    }

    @Test
    void needRebuildJsonParseExceptionReturnsOldAndReleasesLock() {
        when(valueOps.get(anyString())).thenReturn(expiredJson(), "not-json");
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        Shop result = client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList("merchant:lock:shop:1")), any());
    }

    @Test
    void executorRejectedUnlockOnceByRequestThread() {
        doThrow(new RuntimeException("rejected")).when(executor).execute(any(Runnable.class));
        when(valueOps.get(anyString())).thenReturn(expiredJson(), expiredJson());
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        Shop result = client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        // 只由请求线程解锁一次
        verify(redisTemplate, times(1)).execute(any(RedisScript.class), eq(Collections.singletonList("merchant:lock:shop:1")), any());
    }

    @Test
    void executorAcceptedTaskOwnsUnlock() {
        when(valueOps.get(anyString())).thenReturn(expiredJson(), expiredJson());
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        ArgumentCaptor<Runnable> taskCaptor = forClass(Runnable.class);
        doNothing().when(executor).execute(taskCaptor.capture());

        Shop result = client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        // execute 成功后请求线程不再解锁
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), any());
        // 异步任务执行时由任务解锁
        taskCaptor.getValue().run();
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList("merchant:lock:shop:1")), any());
    }

    @Test
    void constructorHasCacheRebuildQualifier() throws Exception {
        Annotation qualifier = CacheClient.class.getConstructor(StringRedisTemplate.class, Executor.class)
                .getParameterAnnotations()[1][0];

        assertThat(qualifier).isInstanceOf(Qualifier.class);
        assertThat(((Qualifier) qualifier).value()).isEqualTo("cacheRebuildExecutor");
    }

    @Test
    void lockNotAcquiredRetriesBounded() throws Exception {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        doNothing().when(spy).sleepBeforeRetry(anyLong());

        assertThatThrownBy(() -> spy.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("缓存重建繁忙");

        verify(valueOps, times(MerchantRedisConstants.CACHE_MUTEX_MAX_ATTEMPTS))
                .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void maxAttemptsDoesNotRecurse() throws Exception {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        doNothing().when(spy).sleepBeforeRetry(anyLong());

        assertThatThrownBy(() -> spy.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES))
                .isInstanceOf(BusinessException.class);

        verify(valueOps, times(MerchantRedisConstants.CACHE_MUTEX_MAX_ATTEMPTS))
                .setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void interruptRestoresFlagAndStopsRetry() throws Exception {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);
        doThrow(new InterruptedException("interrupted")).when(spy).sleepBeforeRetry(anyLong());

        try {
            assertThatThrownBy(() -> spy.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("中断");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void dbNullWritesNullCache() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        Shop result = client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> null, 30L, TimeUnit.MINUTES);

        assertThat(result).isNull();
        verify(valueOps).set(eq("merchant:cache:shop:1"), eq(""), eq(MerchantRedisConstants.CACHE_NULL_TTL), eq(TimeUnit.MINUTES));
    }

    @Test
    void dbSuccessWritesObjectCache() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);

        Shop result = client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        verify(valueOps).set(eq("merchant:cache:shop:1"), anyString(), anyLong(), eq(TimeUnit.SECONDS));
    }

    @Test
    void dbExceptionStillReleasesOwnerLock() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        assertThatThrownBy(() -> client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    throw new RuntimeException("db down");
                }, 30L, TimeUnit.MINUTES))
                .isInstanceOf(RuntimeException.class);

        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList("merchant:lock:shop:1")), any());
    }

    @Test
    void logicalExpireNotExpiredReturnsCache() {
        when(valueOps.get("merchant:cache:shop:1")).thenReturn(freshJson());
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(1L);
                }, 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(dbCalls.get()).isZero();
        verify(executor, never()).execute(any());
    }

    @Test
    void logicalExpireExpiredReturnsOldAndSubmitsRebuild() {
        when(valueOps.get(anyString())).thenReturn(expiredJson());
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        Shop result = client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        verify(executor).execute(any(Runnable.class));
    }

    @Test
    void logicalExpireOtherThreadHoldsLockNoSubmit() {
        when(valueOps.get(anyString())).thenReturn(expiredJson());
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(false);

        Shop result = client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        verify(executor, never()).execute(any());
    }

    @Test
    void logicalExpireSubmissionRejectedReturnsOldAndReleasesLock() {
        doThrow(new RuntimeException("rejected")).when(executor).execute(any(Runnable.class));
        when(valueOps.get(anyString())).thenReturn(expiredJson());
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);

        Shop result = client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList("merchant:lock:shop:1")), any());
    }

    @Test
    void logicalExpireRebuildExceptionReleasesLock() {
        when(valueOps.get(anyString())).thenReturn(expiredJson());
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        ArgumentCaptor<Runnable> taskCaptor = forClass(Runnable.class);
        doNothing().when(executor).execute(taskCaptor.capture());

        client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    throw new RuntimeException("db down");
                }, 30L, TimeUnit.MINUTES);

        assertThatCode(taskCaptor.getValue()::run).doesNotThrowAnyException();
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList("merchant:lock:shop:1")), any());
    }

    @Test
    void logicalExpireLockAcquiredButCacheRefreshedSkipsDb() {
        when(valueOps.get(anyString())).thenReturn(expiredJson(), freshJson());
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        AtomicInteger dbCalls = new AtomicInteger();

        Shop result = client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class,
                id -> {
                    dbCalls.incrementAndGet();
                    return shop(1L);
                }, 30L, TimeUnit.MINUTES);

        assertThat(result.getId()).isEqualTo(1L);
        assertThat(dbCalls.get()).isZero();
        verify(executor, never()).execute(any());
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList("merchant:lock:shop:1")), any());
    }

    @Test
    void logicalExpireDbNullKeepsOldCache() {
        when(valueOps.get(anyString())).thenReturn(expiredJson());
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        ArgumentCaptor<Runnable> taskCaptor = forClass(Runnable.class);
        doNothing().when(executor).execute(taskCaptor.capture());

        client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class, id -> null, 30L, TimeUnit.MINUTES);
        taskCaptor.getValue().run();

        verify(valueOps, never()).set(eq("merchant:cache:shop:1"), anyString());
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList("merchant:lock:shop:1")), any());
    }

    @Test
    void logicalExpireWriteFailureDoesNotCorruptCache() {
        when(valueOps.get(anyString())).thenReturn(expiredJson());
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(valueOps)
                .set(eq("merchant:cache:shop:1"), anyString());
        ArgumentCaptor<Runnable> taskCaptor = forClass(Runnable.class);
        doNothing().when(executor).execute(taskCaptor.capture());

        client.queryWithLogicalExpire("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);

        assertThatCode(taskCaptor.getValue()::run).doesNotThrowAnyException();
        verify(redisTemplate).execute(any(RedisScript.class), eq(Collections.singletonList("merchant:lock:shop:1")), any());
    }

    @Test
    void nullCacheTtlShorterThanNormal() {
        assertThat(MerchantRedisConstants.CACHE_NULL_TTL).isLessThan(MerchantRedisConstants.CACHE_SHOP_TTL);
    }

    @Test
    void jitteredTtlAlwaysPositive() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
        ArgumentCaptor<Long> ttlCaptor = forClass(Long.class);

        for (int i = 0; i < 30; i++) {
            client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);
        }

        verify(valueOps, times(30)).set(eq("merchant:cache:shop:1"), anyString(), ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        for (Long ttl : ttlCaptor.getAllValues()) {
            assertThat(ttl).isPositive();
        }
    }

    @Test
    void jitterRangeWithinContract() {
        when(valueOps.get(anyString())).thenReturn(null);
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any())).thenReturn(1L);
        ArgumentCaptor<Long> ttlCaptor = forClass(Long.class);

        for (int i = 0; i < 30; i++) {
            client.queryWithMutex("merchant:cache:shop:", 1L, Shop.class, id -> shop(1L), 30L, TimeUnit.MINUTES);
        }

        verify(valueOps, times(30)).set(eq("merchant:cache:shop:1"), anyString(), ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        for (Long ttl : ttlCaptor.getAllValues()) {
        assertThat(ttl).isBetween(1800L, 1800L + MerchantRedisConstants.CACHE_TTL_JITTER_SECONDS);
        }
    }

    @Test
    void cacheClientHasNoStaticExecutor() throws Exception {
        for (Field field : CacheClient.class.getDeclaredFields()) {
            if (Executor.class.isAssignableFrom(field.getType())) {
                assertThat(Modifier.isStatic(field.getModifiers())).isFalse();
            }
        }
    }
}
