package com.linklife.merchant.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.merchant.cache.MerchantLocalCache;
import com.linklife.merchant.entity.ShopType;
import com.linklife.merchant.mapper.ShopTypeMapper;
import com.linklife.shared.cache.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShopTypeServiceImpl（Stage5B）：Caffeine L1 → Redis L2 → DB（sort asc）；
 * malformed JSON fail-closed；空列表可缓存；返回不可变列表。
 */
class ShopTypeServiceImplTest {

    private ShopTypeServiceImpl service;
    private ShopTypeMapper shopTypeMapper;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private CacheClient cacheClient;
    private MerchantLocalCache localCache;

    @BeforeEach
    void setUp() {
        service = spy(new ShopTypeServiceImpl());
        shopTypeMapper = mock(ShopTypeMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        cacheClient = mock(CacheClient.class);
        localCache = mock(MerchantLocalCache.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        ReflectionTestUtils.setField(service, "baseMapper", shopTypeMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "cacheClient", cacheClient);
        ReflectionTestUtils.setField(service, "localCache", localCache);
    }

    private static ShopType type(long id, String name, int sort) {
        ShopType t = new ShopType();
        t.setId(id);
        t.setName(name);
        t.setSort(sort);
        return t;
    }

    @Test
    void l1HitReturnsWithoutRedisOrDb() {
        List<ShopType> cached = List.of(type(1L, "美食", 1));
        when(localCache.lookupShopTypes()).thenReturn(Optional.of(cached));

        List<ShopType> result = service.queryTypeListCached();

        assertThat(result).hasSize(1);
        verify(redisTemplate, never()).opsForValue();
        verify(shopTypeMapper, never()).selectList(any());
    }

    @Test
    void l1MissRedisHitSkipsDbAndWarmsL1() {
        List<ShopType> types = List.of(type(1L, "美食", 1), type(2L, "KTV", 2));
        when(localCache.lookupShopTypes()).thenReturn(Optional.empty());
        when(valueOps.get("merchant:cache:shop-type:list")).thenReturn(JSONUtil.toJsonStr(types));

        List<ShopType> result = service.queryTypeListCached();

        assertThat(result).extracting(ShopType::getName).containsExactly("美食", "KTV");
        verify(shopTypeMapper, never()).selectList(any());
        verify(localCache).putShopTypes(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void l1MissRedisMissQueriesDbOnceAndWarmsBoth() {
        List<ShopType> db = List.of(type(2L, "KTV", 2), type(1L, "美食", 1));
        when(localCache.lookupShopTypes()).thenReturn(Optional.empty());
        when(valueOps.get("merchant:cache:shop-type:list")).thenReturn(null);
        when(shopTypeMapper.selectList(any())).thenReturn(db);

        List<ShopType> result = service.queryTypeListCached();

        assertThat(result).extracting(ShopType::getName).containsExactly("KTV", "美食");
        verify(shopTypeMapper, org.mockito.Mockito.times(1)).selectList(any());
        verify(cacheClient).set(eq("merchant:cache:shop-type:list"),
                org.mockito.ArgumentMatchers.anyList(), eq(30L), eq(TimeUnit.MINUTES));
        verify(localCache).putShopTypes(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void dbQueryUsesSortAsc() {
        when(localCache.lookupShopTypes()).thenReturn(Optional.empty());
        when(valueOps.get("merchant:cache:shop-type:list")).thenReturn(null);
        when(shopTypeMapper.selectList(any())).thenReturn(List.of());

        service.queryTypeListCached();

        ArgumentCaptor<Wrapper<ShopType>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(shopTypeMapper).selectList(captor.capture());
        assertThat(String.valueOf(captor.getValue().getSqlSegment())).contains("sort");
    }

    @Test
    void malformedRedisJsonFailsClosed() {
        when(localCache.lookupShopTypes()).thenReturn(Optional.empty());
        when(valueOps.get("merchant:cache:shop-type:list")).thenReturn("{not-json");

        assertThatThrownBy(() -> service.queryTypeListCached())
                .isInstanceOf(BusinessException.class)
                .hasMessage("缓存数据格式错误");
        verify(shopTypeMapper, never()).selectList(any());
    }

    @Test
    void blankRedisValueFailsClosed() {
        when(localCache.lookupShopTypes()).thenReturn(Optional.empty());
        when(valueOps.get("merchant:cache:shop-type:list")).thenReturn("");

        assertThatThrownBy(() -> service.queryTypeListCached())
                .isInstanceOf(BusinessException.class)
                .hasMessage("缓存数据格式错误");
        verify(shopTypeMapper, never()).selectList(any());
    }

    @Test
    void emptyDbListIsCachedAndSecondCallSkipsRedisDb() {
        when(localCache.lookupShopTypes()).thenReturn(Optional.empty());
        when(valueOps.get("merchant:cache:shop-type:list")).thenReturn(null);
        when(shopTypeMapper.selectList(any())).thenReturn(List.of());

        List<ShopType> first = service.queryTypeListCached();
        assertThat(first).isEmpty();
        verify(cacheClient).set(eq("merchant:cache:shop-type:list"),
                org.mockito.ArgumentMatchers.anyList(), eq(30L), eq(TimeUnit.MINUTES));
        verify(localCache).putShopTypes(org.mockito.ArgumentMatchers.anyList());

        when(localCache.lookupShopTypes()).thenReturn(Optional.of(List.of()));
        List<ShopType> second = service.queryTypeListCached();
        assertThat(second).isEmpty();
        verify(valueOps, org.mockito.Mockito.times(1)).get(anyString());
        verify(shopTypeMapper, org.mockito.Mockito.times(1)).selectList(any());
    }
}
