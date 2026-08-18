package com.linklife.merchant.geo;

import com.linklife.merchant.entity.Shop;
import com.linklife.merchant.mapper.ShopMapper;
import com.linklife.merchant.redis.MerchantRedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Merchant-owned Redis GEO index for nearby-shop queries (Final-Audit-R1-B).
 *
 * <p>Key contract: {@code merchant:shop:geo:{typeId}} holds shop ids as members
 * with their final coordinates (x=longitude, y=latitude). The index is rebuilt
 * from MySQL at startup (idempotent), and maintained transactionally via
 * afterCommit on create/update so a rollback never produces a Redis side effect.
 *
 * <p>Redis write failures are logged and never faked as DB rollback; the startup
 * rebuild failure propagates so the service fails fast instead of silently
 * serving an empty index.
 */
@Slf4j
@Service
public class ShopGeoIndexService {

    private static final String GEO_NAMESPACE_PATTERN = MerchantRedisConstants.SHOP_GEO_KEY + "*";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private ShopMapper shopMapper;

    /**
     * Rebuild the whole {@code merchant:shop:geo:*} namespace from tb_shop
     * (authoritative source: id / type_id / x / y). Stale keys are removed via
     * SCAN (never KEYS) and the index is recreated from the current DB state.
     * Any failure propagates to fail startup.
     */
    public void rebuildFromDatabase() {
        List<String> staleKeys = scanGeoKeys();
        if (!staleKeys.isEmpty()) {
            stringRedisTemplate.delete(staleKeys);
            log.info("cleared stale merchant GEO keys count={}", staleKeys.size());
        }
        List<Shop> shops = shopMapper.selectList(null);
        Map<Long, List<Shop>> byType = new LinkedHashMap<>();
        for (Shop shop : shops) {
            if (shop == null || shop.getId() == null || shop.getTypeId() == null
                    || shop.getX() == null || shop.getY() == null) {
                log.warn("skip shop without complete geo data shopId={}", shop == null ? null : shop.getId());
                continue;
            }
            byType.computeIfAbsent(shop.getTypeId(), k -> new ArrayList<>()).add(shop);
        }
        for (Map.Entry<Long, List<Shop>> entry : byType.entrySet()) {
            for (Shop shop : entry.getValue()) {
                add(shop);
            }
        }
        log.info("merchant GEO index rebuilt from database types={} shops={}", byType.size(), shops.size());
    }

    /**
     * Add a freshly created shop to its type GEO index (called afterCommit).
     */
    public void addShop(Shop shop) {
        add(shop);
    }

    /**
     * Maintain the GEO index after a committed update. Never relies on partial
     * client payload as final state: the caller passes the authoritative
     * before/after rows read from MySQL.
     */
    public void updateAfterCommit(Shop before, Shop after) {
        if (before == null || after == null || after.getId() == null
                || after.getTypeId() == null || after.getX() == null || after.getY() == null) {
            log.warn("skip GEO update for incomplete shop state beforeExists={} afterId={}",
                    before != null, after == null ? null : after.getId());
            return;
        }
        boolean sameType = before.getTypeId() != null && before.getTypeId().equals(after.getTypeId());
        boolean sameCoordinates = Objects.equals(before.getX(), after.getX())
                && Objects.equals(before.getY(), after.getY());
        if (sameType && sameCoordinates) {
            return;
        }
        if (before.getTypeId() != null && !sameType) {
            removeFromType(after.getId(), before.getTypeId());
        }
        add(after);
    }

    /**
     * GEOADD member=shopId with final coordinates into {@code merchant:shop:geo:{typeId}}.
     */
    private void add(Shop shop) {
        if (shop == null || shop.getId() == null || shop.getTypeId() == null
                || shop.getX() == null || shop.getY() == null) {
            log.warn("skip GEO add for incomplete shop shopId={}", shop == null ? null : shop.getId());
            return;
        }
        stringRedisTemplate.opsForGeo().add(
                geoKey(shop.getTypeId()),
                new Point(shop.getX(), shop.getY()),
                String.valueOf(shop.getId()));
    }

    private void removeFromType(Long shopId, Long typeId) {
        stringRedisTemplate.opsForGeo().remove(geoKey(typeId), String.valueOf(shopId));
    }

    /**
     * SCAN {@code merchant:shop:geo:*} without blocking; deletes only the GEO
     * namespace, never other merchant keys.
     */
    private List<String> scanGeoKeys() {
        List<String> keys = new ArrayList<>();
        stringRedisTemplate.execute((RedisCallback<List<String>>) connection -> {
            try (Cursor<byte[]> cursor = connection.scan(
                    ScanOptions.scanOptions().match(GEO_NAMESPACE_PATTERN).count(100).build())) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            }
            return keys;
        });
        return keys;
    }

    static String geoKey(Long typeId) {
        return MerchantRedisConstants.SHOP_GEO_KEY + typeId;
    }
}
