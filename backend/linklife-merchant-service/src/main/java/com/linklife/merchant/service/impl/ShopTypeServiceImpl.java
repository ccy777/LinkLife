package com.linklife.merchant.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.linklife.merchant.entity.ShopType;
import com.linklife.merchant.mapper.ShopTypeMapper;
import com.linklife.merchant.service.IShopTypeService;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.merchant.cache.MerchantLocalCache;
import com.linklife.shared.cache.CacheClient;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.linklife.merchant.redis.MerchantRedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.linklife.merchant.redis.MerchantRedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * ShopType（Stage5B）：Caffeine L1 → Redis L2 → MySQL（sort asc）。
 * 空列表可缓存；null 不缓存；Redis JSON malformed fail-closed；返回不可变列表。
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Resource
    private MerchantLocalCache localCache;

    @Override
    public List<ShopType> queryTypeListCached() {
        Optional<List<ShopType>> local = localCache.lookupShopTypes();
        if (local.isPresent()) {
            return local.get();
        }
        String json = stringRedisTemplate.opsForValue().get(CACHE_SHOP_TYPE_KEY);
        if (json != null) {
            if (StrUtil.isBlank(json)) {
                throw new BusinessException("缓存数据格式错误");
            }
            List<ShopType> types;
            try {
                types = JSONUtil.toList(json, ShopType.class);
            } catch (RuntimeException e) {
                log.error("ShopType 缓存 JSON 解析失败 key={}", CACHE_SHOP_TYPE_KEY, e);
                throw new BusinessException("缓存数据格式错误");
            }
            if (types == null) {
                throw new BusinessException("缓存数据格式错误");
            }
            List<ShopType> copy = List.copyOf(types);
            localCache.putShopTypes(copy);
            return copy;
        }
        List<ShopType> dbList = baseMapper.selectList(
                new QueryWrapper<ShopType>().orderByAsc("sort"));
        List<ShopType> result = dbList == null ? List.of() : List.copyOf(dbList);
        cacheClient.set(CACHE_SHOP_TYPE_KEY, result, CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        localCache.putShopTypes(result);
        return result;
    }
}
