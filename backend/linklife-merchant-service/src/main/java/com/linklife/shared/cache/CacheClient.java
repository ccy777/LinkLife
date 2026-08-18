package com.linklife.shared.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.linklife.common.core.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.linklife.merchant.redis.MerchantRedisConstants.CACHE_MUTEX_BASE_WAIT_MILLIS;
import static com.linklife.merchant.redis.MerchantRedisConstants.CACHE_MUTEX_MAX_ATTEMPTS;
import static com.linklife.merchant.redis.MerchantRedisConstants.CACHE_MUTEX_MAX_RETRY_WAIT_MILLIS;
import static com.linklife.merchant.redis.MerchantRedisConstants.CACHE_NULL_TTL;
import static com.linklife.merchant.redis.MerchantRedisConstants.CACHE_TTL_JITTER_SECONDS;
import static com.linklife.merchant.redis.MerchantRedisConstants.LOCK_SHOP_KEY;
import static com.linklife.merchant.redis.MerchantRedisConstants.LOCK_SHOP_TTL;

/**
 * 缓存客户端：穿透保护、互斥重建（owner token + Lua 原子解锁）、逻辑过期异步重建。
 * 重建线程池通过构造器注入 Spring Bean，禁止静态无界线程池。
 */
@Slf4j
@Component
public class CacheClient {

    private static final DefaultRedisScript<Long> CACHE_UNLOCK_SCRIPT;

    static {
        CACHE_UNLOCK_SCRIPT = new DefaultRedisScript<>();
        CACHE_UNLOCK_SCRIPT.setLocation(new ClassPathResource("cache-unlock.lua"));
        CACHE_UNLOCK_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate stringRedisTemplate;
    private final Executor cacheRebuildExecutor;

    public CacheClient(StringRedisTemplate stringRedisTemplate,
                       @Qualifier("cacheRebuildExecutor") Executor cacheRebuildExecutor) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.cacheRebuildExecutor = cacheRebuildExecutor;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        long seconds = jitterSeconds(unit.toSeconds(time));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), seconds, TimeUnit.SECONDS);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期（含 TTL 抖动）
        long seconds = jitterSeconds(unit.toSeconds(time));
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(seconds));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    public <R, ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            // 命中空值
            return null;
        }
        R r = dbFallback.apply(id);
        if (r == null) {
            // 空值缓存 TTL 短于正常对象 TTL
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        this.set(key, r, time, unit);
        return r;
    }

    /**
     * 互斥查询：有限循环 + double-check，禁止递归；锁使用唯一 owner 并在 finally 原子解锁。
     */
    public <R, ID> R queryWithMutex(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = LOCK_SHOP_KEY + id;
        String owner = newLockOwner();

        // 1.进入时单快照读取（一次 GET）
        CacheSnapshot<R> snapshot = readSnapshot(key, type);
        if (snapshot.hit) {
            return snapshot.value;
        }
        if (snapshot.nullHit) {
            return null;
        }

        // 2.有限循环尝试获取互斥锁
        boolean locked = false;
        for (int attempt = 1; attempt <= CACHE_MUTEX_MAX_ATTEMPTS; attempt++) {
            try {
                locked = tryLock(lockKey, owner, LOCK_SHOP_TTL);
            } catch (Exception e) {
                log.error("获取缓存重建锁失败 key={}", key, e);
                throw new BusinessException("缓存服务暂时不可用");
            }
            if (locked) {
                break;
            }
            if (attempt >= CACHE_MUTEX_MAX_ATTEMPTS) {
                break;
            }
            // 短暂等待后再次查缓存，若已被其他线程填充则直接返回
            try {
                sleepBeforeRetry(waitMillis(attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("缓存重建被中断");
            }
            // 等待后单快照复查
            CacheSnapshot<R> reSnapshot = readSnapshot(key, type);
            if (reSnapshot.hit) {
                return reSnapshot.value;
            }
            if (reSnapshot.nullHit) {
                return null;
            }
        }
        if (!locked) {
            // 达到上限：最终单快照复查，仍 MISS 才抛繁忙异常
            CacheSnapshot<R> finalSnapshot = readSnapshot(key, type);
            if (finalSnapshot.hit) {
                return finalSnapshot.value;
            }
            if (finalSnapshot.nullHit) {
                return null;
            }
            throw new BusinessException("缓存重建繁忙，请稍后再试");
        }

        // 3.获得锁后 double-check 单快照，确认仍未命中才回源
        try {
            CacheSnapshot<R> doubleChecked = readSnapshot(key, type);
            if (doubleChecked.hit) {
                return doubleChecked.value;
            }
            if (doubleChecked.nullHit) {
                return null;
            }
            R r = dbFallback.apply(id);
            if (r == null) {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.set(key, r, time, unit);
            return r;
        } finally {
            // 只释放当前 owner 的锁；解锁异常不覆盖业务结果
            unlockByOwner(lockKey, owner);
        }
    }

    /**
     * 逻辑过期查询：未过期返回缓存；已过期返回旧值并尝试异步重建。
     */
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String lockKey = LOCK_SHOP_KEY + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return null;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
            return r;
        }
        // 已过期：尝试获取重建锁
        String owner = newLockOwner();
        boolean locked;
        try {
            locked = tryLock(lockKey, owner, LOCK_SHOP_TTL);
        } catch (Exception e) {
            log.error("获取缓存重建锁失败 key={}", key, e);
            return r;
        }
        if (!locked) {
            // 其他线程正在重建，直接返回旧值
            return r;
        }
        // 锁责任转交：请求线程默认负责解锁，execute 明确成功后才转交异步任务
        boolean lockTransferredToTask = false;
        try {
            // 获锁后再次确认仍过期，避免重复重建
            if (needRebuild(key)) {
                try {
                    cacheRebuildExecutor.execute(() ->
                            rebuildLogicalCache(key, lockKey, owner, id, type, dbFallback, time, unit));
                    lockTransferredToTask = true;
                } catch (Exception e) {
                    // 提交失败：记录告警，由请求线程 finally 解锁
                    log.error("缓存重建任务提交失败 key={}", key, e);
                }
            }
        } catch (Exception e) {
            // double-check 的 GET/JSON/时间读取异常：记录告警，返回旧值，finally 解锁
            log.error("缓存重建 double-check 失败 key={}", key, e);
        } finally {
            if (!lockTransferredToTask) {
                unlockByOwner(lockKey, owner);
            }
        }
        return r;
    }

    /**
     * 异步重建：不递归提交；DB 为 null 时保留旧缓存；任何异常都释放锁。
     */
    private <R, ID> void rebuildLogicalCache(String key, String lockKey, String owner, ID id, Class<R> type,
                                             Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        try {
            R newR = dbFallback.apply(id);
            if (newR == null) {
                // 明确策略：数据库无数据时保留旧缓存，不写无法解析的结构
                log.warn("缓存重建查询为空，保留旧缓存 key={}", key);
                return;
            }
            setWithLogicalExpire(key, newR, time, unit);
        } catch (Exception e) {
            log.error("缓存重建执行失败 key={}", key, e);
        } finally {
            unlockByOwner(lockKey, owner);
        }
    }

    private boolean needRebuild(String key) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(json)) {
            return true;
        }
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        return !redisData.getExpireTime().isAfter(LocalDateTime.now());
    }

    /**
     * 单次缓存快照：一次 Redis GET 得到 HIT / NULL_HIT / MISS。
     */
    private <R> CacheSnapshot<R> readSnapshot(String key, Class<R> type) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            try {
                return CacheSnapshot.hit(JSONUtil.toBean(json, type));
            } catch (RuntimeException e) {
                // malformed JSON：不得伪装为空值，抛稳定失败
                log.error("缓存 JSON 解析失败 key={}", key, e);
                throw new BusinessException("缓存数据格式错误");
            }
        }
        return json == null ? CacheSnapshot.miss() : CacheSnapshot.nullHit();
    }

    private static final class CacheSnapshot<R> {
        private final boolean hit;
        private final boolean nullHit;
        private final R value;

        private CacheSnapshot(boolean hit, boolean nullHit, R value) {
            this.hit = hit;
            this.nullHit = nullHit;
            this.value = value;
        }

        static <R> CacheSnapshot<R> hit(R value) {
            return new CacheSnapshot<>(true, false, value);
        }

        static <R> CacheSnapshot<R> nullHit() {
            return new CacheSnapshot<>(false, true, null);
        }

        static <R> CacheSnapshot<R> miss() {
            return new CacheSnapshot<>(false, false, null);
        }
    }

    /**
     * 生成唯一 owner：UUID + 当前线程 id。
     */
    String newLockOwner() {
        return UUID.randomUUID().toString() + "-" + Thread.currentThread().getId();
    }

    /**
     * 获取锁：setIfAbsent 原子设置 owner 与 TTL；null 或异常 fail-closed。
     */
    private boolean tryLock(String lockKey, String owner, long ttlSeconds) {
        if (ttlSeconds <= 0L) {
            throw new IllegalArgumentException("锁 TTL 必须为正");
        }
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, owner, ttlSeconds, TimeUnit.SECONDS);
        if (acquired == null) {
            throw new IllegalStateException("获取缓存重建锁结果未知");
        }
        return acquired;
    }

    /**
     * 原子解锁：仅 owner 匹配时删除；null/异常仅告警，不直接 delete 其他线程的锁。
     */
    void unlockByOwner(String lockKey, String owner) {
        try {
            Long result = stringRedisTemplate.execute(
                    CACHE_UNLOCK_SCRIPT, Collections.singletonList(lockKey), owner);
            if (result == null || (result != 0L && result != 1L)) {
                log.warn("缓存解锁结果未知 lockKey={}", lockKey);
            }
        } catch (Exception e) {
            log.warn("缓存解锁失败 lockKey={}", lockKey, e);
        }
    }

    /**
     * 有限等待，可测试注入。
     */
    void sleepBeforeRetry(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private long waitMillis(int attempt) {
        return Math.min(CACHE_MUTEX_BASE_WAIT_MILLIS * attempt, CACHE_MUTEX_MAX_RETRY_WAIT_MILLIS);
    }

    /**
     * TTL 抖动：基础 TTL 必须为正，结果 TTL = base + [0, CACHE_TTL_JITTER_SECONDS]。
     */
    long jitterSeconds(long baseSeconds) {
        if (baseSeconds <= 0L) {
            throw new IllegalArgumentException("TTL 必须为正");
        }
        return baseSeconds + ThreadLocalRandom.current().nextLong(CACHE_TTL_JITTER_SECONDS + 1L);
    }
}
