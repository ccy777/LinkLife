package com.linklife.merchant.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.linklife.common.core.api.Result;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.common.core.util.SystemConstants;
import com.linklife.merchant.cache.MerchantLocalCache;
import com.linklife.merchant.entity.Shop;
import com.linklife.merchant.geo.ShopGeoCoordinateValidator;
import com.linklife.merchant.geo.ShopGeoIndexService;
import com.linklife.merchant.mapper.ShopMapper;
import com.linklife.merchant.service.IShopService;
import com.linklife.shared.cache.CacheClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.linklife.merchant.redis.MerchantRedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {


    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private MerchantLocalCache localCache;

    @Resource
    private ShopGeoIndexService geoIndexService;

    @Resource
    private ShopGeoCoordinateValidator geoCoordinateValidator;

    @Override
    public Result queryById(Long id) {
        // Stage5B：Caffeine L1 → Redis L2（mutex）→ MySQL；绝不 L1 miss 直接 DB。
        Optional<Optional<Shop>> local = localCache.lookupShop(id);
        if (local.isPresent()) {
            return local.get().map(Result::ok).orElseGet(() -> Result.fail("店铺不存在！"));
        }
        // Stage5B-R2：L1 MISS 后先记录本地 epoch，L2/DB 加载期间若发生 invalidate，
        // 旧结果不得再写回 L1（in-flight refill fence）。
        long epoch = localCache.snapshotShopEpoch(id);
        Shop shop = cacheClient.queryWithMutex(
                CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
        if (shop == null) {
            localCache.putShopMissingIfEpochUnchanged(id, epoch);
            return Result.fail("店铺不存在！");
        }
        localCache.putShopIfEpochUnchanged(shop, epoch);
        return Result.ok(shop);
    }

    @Override
    @Transactional
    public Result createShop(Shop shop) {
        if (shop == null) {
            throw new BusinessException("店铺信息不合法");
        }
        // Final-Audit-R2-A：DB commit 前校验最终 create x/y，非法坐标不得落库，
        // 也不会产生 afterCommit GEO side effect。
        geoCoordinateValidator.requireValid(shop.getX(), shop.getY());
        boolean saved = save(shop);
        if (!saved) {
            throw new BusinessException("店铺保存失败");
        }
        if (shop.getId() == null) {
            throw new BusinessException("店铺保存失败");
        }
        Long id = shop.getId();
        // Final-Audit-R1-B：以 MySQL 提交后的完整行作为 GEO 索引的事实源。
        Shop created = getById(id);
        // 事务提交后失效：当前实例 Caffeine + Redis L2，避免创建前形成的 negative cache 继续命中。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                geoAfterCommit(() -> geoIndexService.addShop(created));
                evictShopCacheAfterCommit(id);
            }
        });
        return Result.ok(id);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        // Final-Audit-R1-B：读取 MySQL 权威的 before/after 状态，
        // 不依赖客户端 partial update 中未传字段作为 final state。
        Shop before = getById(id);
        // Final-Audit-R2-A：用 before + patch 计算 final 坐标，在 updateById 前校验；
        // 非法坐标不得写 DB，也不得注册 afterCommit GEO/cache side effect。
        if (before != null) {
            Double finalX = shop.getX() != null ? shop.getX() : before.getX();
            Double finalY = shop.getY() != null ? shop.getY() : before.getY();
            geoCoordinateValidator.requireValid(finalX, finalY);
        }
        // 1.更新数据库
        boolean updated = updateById(shop);
        if (!updated) {
            // 更新失败：不返回成功，不删除缓存
            return Result.fail("店铺更新失败");
        }
        Shop after = getById(id);
        // 2.注册事务提交后回调：只有提交成功才删除缓存；回滚不删除
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                geoAfterCommit(() -> geoIndexService.updateAfterCommit(before, after));
                evictShopCacheAfterCommit(id);
            }
        });
        return Result.ok();
    }

    /**
     * GEO 索引维护：Redis 写失败仅记录错误，不伪装 DB 回滚；
     * 缓存失效仍必须继续执行（startup rebuild 是恢复机制）。
     */
    private void geoAfterCommit(Runnable geoWrite) {
        try {
            geoWrite.run();
        } catch (Exception e) {
            log.error("店铺 GEO 索引维护失败，不伪装 DB 回滚（启动重建会恢复）", e);
        }
    }

    /**
     * 事务提交后的 Shop 缓存失效（Stage5B-R1 收口并发竞态）。
     *
     * <p>顺序必须为：先尝试删除共享 Redis L2，finally 中最后失效当前实例 Caffeine L1。
     * 若先失效 L1，并发 reader 可在 Redis delete 前把旧 L2 值回填进 L1；
     * 只有把本地 invalidate 放在流程最后一步，才能清掉该回填值。
     * Redis 删除失败仅记录错误，不伪装事务回滚；L1 仍必须失效。</p>
     */
    private void evictShopCacheAfterCommit(Long id) {
        try {
            Boolean deleted = stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
            if (deleted == null) {
                log.error("店铺缓存删除结果未知 shopId={}", id);
            } else if (!deleted) {
                log.debug("店铺缓存原本不存在，视为已失效 shopId={}", id);
            }
        } catch (Exception e) {
            log.error("店铺缓存删除失败 shopId={}", id, e);
        } finally {
            localCache.invalidateShop(id);
        }
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // Final-Audit-R2-A：x/y 均提供时先校验坐标，再执行 GEOSEARCH；
        // 非法坐标返回明确业务错误，不得让 Redis exception 变成 500。
        geoCoordinateValidator.requireValid(x, y);

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }
}
