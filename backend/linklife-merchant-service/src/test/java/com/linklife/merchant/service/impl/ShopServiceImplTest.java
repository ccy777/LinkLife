package com.linklife.merchant.service.impl;

import com.linklife.common.core.api.Result;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.merchant.cache.MerchantLocalCache;
import com.linklife.merchant.entity.Shop;
import com.linklife.merchant.geo.ShopGeoCoordinateValidator;
import com.linklife.merchant.geo.ShopGeoIndexService;
import com.linklife.merchant.mapper.ShopMapper;
import com.linklife.shared.cache.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.geo.Distance;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShopServiceImpl（Stage5B）：Caffeine L1 positive/negative/miss → Redis mutex → L1 回填；
 * update/create 事务提交后 L1 + Redis 失效语义。
 */
class ShopServiceImplTest {

    private ShopServiceImpl service;
    private ShopMapper shopMapper;
    private StringRedisTemplate redisTemplate;
    private CacheClient cacheClient;
    private MerchantLocalCache localCache;
    private ShopGeoIndexService geoIndexService;

    @BeforeEach
    void setUp() {
        service = spy(new ShopServiceImpl());
        shopMapper = mock(ShopMapper.class);
        redisTemplate = mock(StringRedisTemplate.class);
        cacheClient = mock(CacheClient.class);
        localCache = mock(MerchantLocalCache.class);
        geoIndexService = mock(ShopGeoIndexService.class);
        ReflectionTestUtils.setField(service, "baseMapper", shopMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "cacheClient", cacheClient);
        ReflectionTestUtils.setField(service, "localCache", localCache);
        ReflectionTestUtils.setField(service, "geoIndexService", geoIndexService);
        ReflectionTestUtils.setField(service, "geoCoordinateValidator", new ShopGeoCoordinateValidator());
    }

    private Shop shop(Long id) {
        Shop shop = new Shop();
        shop.setId(id);
        return shop;
    }

    // ---------- queryById：L1 ----------

    @Test
    void l1PositiveHitReturnsWithoutRedisOrDb() {
        Shop cached = shop(1L);
        cached.setName("l1-name");
        when(localCache.lookupShop(1L)).thenReturn(Optional.of(Optional.of(cached)));

        Result result = service.queryById(1L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(((Shop) result.getData()).getName()).isEqualTo("l1-name");
        verify(cacheClient, never()).queryWithMutex(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void l1NegativeHitReturnsMissingWithoutRedis() {
        when(localCache.lookupShop(1L)).thenReturn(Optional.of(Optional.empty()));

        Result result = service.queryById(1L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("店铺不存在！");
        verify(cacheClient, never()).queryWithMutex(anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void l1MissUsesRedisMutexAndWarmsPositive() {
        when(localCache.lookupShop(1L)).thenReturn(Optional.empty());
        when(localCache.snapshotShopEpoch(1L)).thenReturn(42L);
        Shop fromDb = shop(1L);
        fromDb.setName("db-name");
        when(cacheClient.queryWithMutex(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(fromDb);

        Result result = service.queryById(1L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(((Shop) result.getData()).getName()).isEqualTo("db-name");
        // A. L1 miss 必须先 snapshot epoch，再进入 L2/DB 加载。
        InOrder loadOrder = inOrder(localCache, cacheClient);
        loadOrder.verify(localCache).snapshotShopEpoch(1L);
        loadOrder.verify(cacheClient).queryWithMutex(
                org.mockito.ArgumentMatchers.eq("merchant:cache:shop:"),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(Shop.class),
                any(),
                org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.MINUTES));
        // B. positive 回填必须走 conditional API，禁止无条件 putShop。
        verify(cacheClient).queryWithMutex(
                org.mockito.ArgumentMatchers.eq("merchant:cache:shop:"),
                org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq(Shop.class),
                any(),
                org.mockito.ArgumentMatchers.eq(30L),
                org.mockito.ArgumentMatchers.eq(TimeUnit.MINUTES));
        verify(localCache).putShopIfEpochUnchanged(fromDb, 42L);
    }

    @Test
    void l1MissWithRedisNullWritesNegative() {
        when(localCache.lookupShop(1L)).thenReturn(Optional.empty());
        when(localCache.snapshotShopEpoch(1L)).thenReturn(5L);
        when(cacheClient.queryWithMutex(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(null);

        Result result = service.queryById(1L);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("店铺不存在！");
        // C. negative 回填必须走 conditional API，禁止无条件 putShopMissing。
        verify(localCache).putShopMissingIfEpochUnchanged(1L, 5L);
    }

    @Test
    void inflightRefillFenceRejectsStaleFillWithoutUnconditionalPut() {
        // 解释性竞态：reader 在 Redis delete 前已读到旧 Shop 并暂停；
        // updater 完成 delete Redis + invalidate L1 后，reader 恢复执行 conditional fill，
        // 因 epoch 已变化被拒绝：请求按已读取结果完成，但旧值不写入 L1。
        when(localCache.lookupShop(1L)).thenReturn(Optional.empty());
        when(localCache.snapshotShopEpoch(1L)).thenReturn(7L);
        Shop oldShop = shop(1L);
        oldShop.setName("old");
        when(cacheClient.queryWithMutex(anyString(), any(), any(), any(), any(), any()))
                .thenReturn(oldShop);
        when(localCache.putShopIfEpochUnchanged(oldShop, 7L)).thenReturn(false);

        Result result = service.queryById(1L);

        assertThat(result.getSuccess()).isTrue();
        assertThat(((Shop) result.getData()).getName()).isEqualTo("old");
        verify(localCache).putShopIfEpochUnchanged(oldShop, 7L);
        verify(localCache, never()).putShopMissingIfEpochUnchanged(any(), anyLong());
    }

    // ---------- update：事务提交后失效 ----------

    @Test
    void nullIdFails() {
        Result result = service.update(shop(null));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("店铺id不能为空");
        verify(shopMapper, never()).updateById(any(Shop.class));
    }

    @Test
    void updateByIdFalseDoesNotInvalidateAnything() {
        when(shopMapper.updateById(any(Shop.class))).thenReturn(0);

        Result result = service.update(shop(1L));

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("店铺更新失败");
        verify(redisTemplate, never()).delete(anyString());
        verify(localCache, never()).invalidateShop(any());
    }

    @Test
    void cacheNotInvalidatedBeforeCommit() {
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            Result result = service.update(shop(1L));

            assertThat(result.getSuccess()).isTrue();
            verify(redisTemplate, never()).delete("merchant:cache:shop:1");
            verify(localCache, never()).invalidateShop(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void afterCommitInvalidatesL1AndDeletesRedis() {
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        when(redisTemplate.delete("merchant:cache:shop:1")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(shop(1L));

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);
            synchronizations.get(0).afterCommit();

            // 顺序 Gate：必须先删除共享 Redis L2，最后才失效当前实例 Caffeine L1。
            InOrder inOrder = inOrder(localCache, redisTemplate);
            inOrder.verify(redisTemplate).delete("merchant:cache:shop:1");
            inOrder.verify(localCache).invalidateShop(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void afterCommitL1InvalidateIsTheFinalStepGuardingConcurrentRefill() {
        // 竞态解释：如果先 invalidate L1，并发 reader 可在 Redis delete 前读到旧 L2，
        // 并把旧值重新回填进当前实例 L1；因此最终本地 invalidate 必须是失效流程的最后一步。
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        when(redisTemplate.delete("merchant:cache:shop:1")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(shop(1L));

            TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

            InOrder inOrder = inOrder(localCache, redisTemplate);
            inOrder.verify(redisTemplate).delete("merchant:cache:shop:1");
            inOrder.verify(localCache).invalidateShop(1L);
            inOrder.verifyNoMoreInteractions();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackDoesNotInvalidateAnything() {
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(shop(1L));

            TransactionSynchronizationManager.getSynchronizations().get(0)
                    .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(localCache, never()).invalidateShop(any());
            verify(redisTemplate, never()).delete("merchant:cache:shop:1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteFalseTreatedAsAlreadyInvalidated() {
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        when(redisTemplate.delete("merchant:cache:shop:1")).thenReturn(false);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(shop(1L));

            assertThatCode(() ->
                    TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit())
                    .doesNotThrowAnyException();
            verify(localCache).invalidateShop(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteExceptionKeepsL1InvalidatedWithoutFakeRollback() {
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        doThrow(new RuntimeException("redis down")).when(redisTemplate).delete("merchant:cache:shop:1");
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(shop(1L));

            assertThatCode(() ->
                    TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit())
                    .doesNotThrowAnyException();
            // finally 语义：即使 Redis delete 抛异常，L1 invalidate 仍必须执行，
            // 且发生在 delete 尝试之后。
            InOrder inOrder = inOrder(localCache, redisTemplate);
            inOrder.verify(redisTemplate).delete("merchant:cache:shop:1");
            inOrder.verify(localCache).invalidateShop(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void deleteNullLoggedWithoutFakeRollback() {
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        when(redisTemplate.delete("merchant:cache:shop:1")).thenReturn(null);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(shop(1L));

            assertThatCode(() ->
                    TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit())
                    .doesNotThrowAnyException();
            verify(localCache).invalidateShop(1L);
            verify(redisTemplate).delete("merchant:cache:shop:1");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- createShop：negative cache 失效 ----------

    @Test
    void createShopAfterCommitInvalidatesL1AndRedis() {
        Shop input = shop(null);
        input.setTypeId(1L);
        input.setX(120.1);
        input.setY(30.2);
        doAnswer(invocation -> {
            invocation.getArgument(0, Shop.class).setId(7L);
            return 1;
        }).when(shopMapper).insert(any(Shop.class));
        when(redisTemplate.delete("merchant:cache:shop:7")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            Result result = service.createShop(input);

            assertThat(result.getSuccess()).isTrue();
            assertThat(result.getData()).isEqualTo(7L);
            verify(localCache, never()).invalidateShop(any());
            verify(redisTemplate, never()).delete(anyString());

            TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

            // 顺序 Gate：create 的 afterCommit 与 update 一致——先删 Redis，最后失效 L1。
            InOrder inOrder = inOrder(localCache, redisTemplate);
            inOrder.verify(redisTemplate).delete("merchant:cache:shop:7");
            inOrder.verify(localCache).invalidateShop(7L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createShopInsertFailureThrowsWithoutCacheOps() {
        Shop input = shop(null);
        input.setTypeId(1L);
        input.setX(120.1);
        input.setY(30.2);
        when(shopMapper.insert(any(Shop.class))).thenReturn(0);

        assertThatThrownBy(() -> service.createShop(input))
                .isInstanceOf(BusinessException.class)
                .hasMessage("店铺保存失败");
        verify(localCache, never()).invalidateShop(any());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void createShopWithoutGeneratedIdThrowsWithoutCacheOps() {
        Shop input = shop(null);
        input.setTypeId(1L);
        input.setX(120.1);
        input.setY(30.2);
        when(shopMapper.insert(any(Shop.class))).thenReturn(1);

        assertThatThrownBy(() -> service.createShop(input))
                .isInstanceOf(BusinessException.class)
                .hasMessage("店铺保存失败");
        verify(localCache, never()).invalidateShop(any());
        verify(redisTemplate, never()).delete(anyString());
    }

    @Test
    void createShopNullThrows() {
        assertThatThrownBy(() -> service.createShop(null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("店铺信息不合法");
    }

    // ---------- Final-Audit-R1-B：GEO afterCommit / rollback ----------

    @Test
    void createAfterCommitAddsShopToGeoIndex() {
        Shop created = shop(7L);
        created.setTypeId(1L);
        created.setX(120.1);
        created.setY(30.2);
        Shop input = shop(null);
        input.setTypeId(1L);
        input.setX(120.1);
        input.setY(30.2);
        doAnswer(invocation -> {
            invocation.getArgument(0, Shop.class).setId(7L);
            return 1;
        }).when(shopMapper).insert(any(Shop.class));
        when(shopMapper.selectById(7L)).thenReturn(created);
        when(redisTemplate.delete("merchant:cache:shop:7")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createShop(input);

            TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

            verify(geoIndexService).addShop(created);
            verify(redisTemplate).delete("merchant:cache:shop:7");
            verify(localCache).invalidateShop(7L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createRollbackDoesNotTouchGeoIndex() {
        Shop input = shop(null);
        input.setTypeId(1L);
        input.setX(120.1);
        input.setY(30.2);
        doAnswer(invocation -> {
            invocation.getArgument(0, Shop.class).setId(7L);
            return 1;
        }).when(shopMapper).insert(any(Shop.class));
        when(shopMapper.selectById(7L)).thenReturn(shop(7L));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.createShop(input);

            TransactionSynchronizationManager.getSynchronizations().get(0)
                    .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(geoIndexService, never()).addShop(any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createInsertFailureDoesNotTouchGeoIndex() {
        Shop input = shop(null);
        input.setTypeId(1L);
        input.setX(120.1);
        input.setY(30.2);
        when(shopMapper.insert(any(Shop.class))).thenReturn(0);

        assertThatThrownBy(() -> service.createShop(input))
                .isInstanceOf(BusinessException.class);
        verify(geoIndexService, never()).addShop(any());
    }

    @Test
    void updateAfterCommitMaintainsGeoIndexWithAuthoritativeStates() {
        Shop before = shop(1L);
        before.setTypeId(1L);
        before.setX(120.1);
        before.setY(30.2);
        Shop after = shop(1L);
        after.setTypeId(2L);
        after.setX(121.0);
        after.setY(31.0);
        when(shopMapper.selectById(1L)).thenReturn(before, after);
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        when(redisTemplate.delete("merchant:cache:shop:1")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(shop(1L));

            TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

            verify(geoIndexService).updateAfterCommit(before, after);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void updateRollbackDoesNotTouchGeoIndex() {
        when(shopMapper.selectById(1L)).thenReturn(shopWithCoords(1L, 1L, 120.1, 30.2));
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(shop(1L));

            TransactionSynchronizationManager.getSynchronizations().get(0)
                    .afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

            verify(geoIndexService, never()).updateAfterCommit(any(), any());
            verify(localCache, never()).invalidateShop(any());
            verify(redisTemplate, never()).delete(anyString());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void updateFailureDoesNotTouchGeoIndex() {
        when(shopMapper.updateById(any(Shop.class))).thenReturn(0);

        Result result = service.update(shop(1L));

        assertThat(result.getSuccess()).isFalse();
        verify(geoIndexService, never()).updateAfterCommit(any(), any());
    }

    @Test
    void geoIndexFailureDoesNotFakeDbRollbackAndCacheEvictionStillRuns() {
        Shop before = shop(1L);
        before.setTypeId(1L);
        before.setX(120.1);
        before.setY(30.2);
        Shop after = shop(1L);
        after.setTypeId(1L);
        after.setX(120.2);
        after.setY(30.3);
        when(shopMapper.selectById(1L)).thenReturn(before, after);
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        when(redisTemplate.delete("merchant:cache:shop:1")).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(geoIndexService).updateAfterCommit(before, after);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.update(shop(1L));

            assertThatCode(() ->
                    TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit())
                    .doesNotThrowAnyException();
            verify(geoIndexService).updateAfterCommit(before, after);
            verify(redisTemplate).delete("merchant:cache:shop:1");
            verify(localCache).invalidateShop(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ---------- Final-Audit-R2-A：GEO 坐标输入边界 ----------

    private Shop shopWithCoords(Long id, Long typeId, Double x, Double y) {
        Shop shop = shop(id);
        shop.setTypeId(typeId);
        shop.setX(x);
        shop.setY(y);
        return shop;
    }

    @Test
    void createLongitudeOutOfRangeRejectedBeforeInsert() {
        assertThatThrownBy(() -> service.createShop(shopWithCoords(null, 1L, 181.0, 30.0)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商铺坐标不合法");
        verify(shopMapper, never()).insert(any(Shop.class));
        verify(geoIndexService, never()).addShop(any());
    }

    @Test
    void createLatitudeOutOfRangeRejectedBeforeInsert() {
        assertThatThrownBy(() -> service.createShop(shopWithCoords(null, 1L, 120.0, 90.0)))
                .isInstanceOf(BusinessException.class);
        verify(shopMapper, never()).insert(any(Shop.class));
    }

    @Test
    void createNanAndInfinityRejectedBeforeInsert() {
        assertThatThrownBy(() -> service.createShop(shopWithCoords(null, 1L, Double.NaN, 30.0)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.createShop(shopWithCoords(null, 1L, 120.0, Double.POSITIVE_INFINITY)))
                .isInstanceOf(BusinessException.class);
        verify(shopMapper, never()).insert(any(Shop.class));
    }

    @Test
    void updateInvalidXRejectedBeforeDbUpdate() {
        Shop before = shopWithCoords(1L, 1L, 120.1, 30.2);
        Shop patch = shop(1L);
        patch.setX(181.0);
        when(shopMapper.selectById(1L)).thenReturn(before);

        assertThatThrownBy(() -> service.update(patch))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商铺坐标不合法");
        verify(shopMapper, never()).updateById(any(Shop.class));
        verify(redisTemplate, never()).delete(anyString());
        verify(localCache, never()).invalidateShop(any());
        verify(geoIndexService, never()).updateAfterCommit(any(), any());
    }

    @Test
    void updateInvalidYRejectedBeforeDbUpdate() {
        Shop before = shopWithCoords(1L, 1L, 120.1, 30.2);
        Shop patch = shop(1L);
        patch.setY(90.0);
        when(shopMapper.selectById(1L)).thenReturn(before);

        assertThatThrownBy(() -> service.update(patch))
                .isInstanceOf(BusinessException.class);
        verify(shopMapper, never()).updateById(any(Shop.class));
    }

    @Test
    void updateOmittedCoordinateUsesBeforeFinalState() {
        Shop before = shopWithCoords(1L, 1L, 120.1, 30.2);
        Shop after = shopWithCoords(1L, 1L, 120.1, 30.2);
        Shop patch = shop(1L);
        patch.setName("name-only");
        when(shopMapper.selectById(1L)).thenReturn(before, after);
        when(shopMapper.updateById(any(Shop.class))).thenReturn(1);
        when(redisTemplate.delete("merchant:cache:shop:1")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            Result result = service.update(patch);
            assertThat(result.getSuccess()).isTrue();

            TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();

            verify(geoIndexService).updateAfterCommit(before, after);
            assertThat(after.getX()).isEqualTo(before.getX());
            assertThat(after.getY()).isEqualTo(before.getY());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void nearbyQueryInvalidXRejectedBeforeRedis() {
        assertThatThrownBy(() -> service.queryShopByType(1, 1, 181.0, 30.0))
                .isInstanceOf(BusinessException.class)
                .hasMessage("商铺坐标不合法");
        verify(redisTemplate, never()).opsForGeo();
    }

    @Test
    void nearbyQueryInvalidYRejectedBeforeRedis() {
        assertThatThrownBy(() -> service.queryShopByType(1, 1, 120.0, 90.0))
                .isInstanceOf(BusinessException.class);
        verify(redisTemplate, never()).opsForGeo();
    }

    @Test
    void nearbyQueryValidCoordinatesUseGeoSearch() {
        @SuppressWarnings("unchecked")
        GeoOperations<String, String> geoOps = mock(GeoOperations.class);
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
        when(geoOps.search(anyString(), any(GeoReference.class), any(Distance.class),
                any(RedisGeoCommands.GeoSearchCommandArgs.class))).thenReturn(null);

        Result result = service.queryShopByType(1, 1, 120.1, 30.2);

        assertThat(result.getSuccess()).isTrue();
        assertThat(((java.util.List<?>) result.getData())).isEmpty();
        verify(redisTemplate).opsForGeo();
        verify(geoOps).search(anyString(), any(GeoReference.class), any(Distance.class),
                any(RedisGeoCommands.GeoSearchCommandArgs.class));
    }
}
