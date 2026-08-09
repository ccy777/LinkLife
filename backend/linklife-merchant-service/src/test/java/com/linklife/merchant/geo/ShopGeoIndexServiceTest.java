package com.linklife.merchant.geo;

import com.linklife.merchant.entity.Shop;
import com.linklife.merchant.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Final-Audit-R1-B: Merchant GEO index lifecycle.
 */
class ShopGeoIndexServiceTest {

    private ShopGeoIndexService service;
    private StringRedisTemplate redisTemplate;
    private ShopMapper shopMapper;
    @SuppressWarnings("unchecked")
    private final GeoOperations<String, String> geoOps = mock(GeoOperations.class);

    @BeforeEach
    void setUp() {
        service = new ShopGeoIndexService();
        redisTemplate = mock(StringRedisTemplate.class);
        shopMapper = mock(ShopMapper.class);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "shopMapper", shopMapper);
        when(redisTemplate.opsForGeo()).thenReturn(geoOps);
    }

    private Shop shop(Long id, Long typeId, Double x, Double y) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setTypeId(typeId);
        shop.setX(x);
        shop.setY(y);
        return shop;
    }

    @SuppressWarnings("unchecked")
    private void stubScan(String... keys) {
        Cursor<byte[]> cursor = mock(Cursor.class);
        Boolean[] hasNextSeq = new Boolean[keys.length + 1];
        java.util.Arrays.fill(hasNextSeq, Boolean.TRUE);
        hasNextSeq[keys.length] = Boolean.FALSE;
        when(cursor.hasNext()).thenReturn(hasNextSeq[0],
                java.util.Arrays.copyOfRange(hasNextSeq, 1, hasNextSeq.length));
        byte[][] nextSeq = new byte[keys.length][];
        for (String k : keys) {
            nextSeq[java.util.Arrays.asList(keys).indexOf(k)] = k.getBytes(StandardCharsets.UTF_8);
        }
        if (keys.length > 0) {
            when(cursor.next()).thenReturn(nextSeq[0],
                    java.util.Arrays.copyOfRange(nextSeq, 1, nextSeq.length));
        }
        RedisConnection connection = mock(RedisConnection.class);
        when(connection.scan(any(ScanOptions.class))).thenReturn(cursor);
        when(redisTemplate.execute(any(RedisCallback.class))).thenAnswer(inv ->
                inv.getArgument(0, RedisCallback.class).doInRedis(connection));
    }

    @Test
    void rebuildCleansGeoNamespaceAndGroupsShopsByType() {
        stubScan("merchant:shop:geo:1", "merchant:shop:geo:9");
        Shop s1 = shop(1L, 1L, 120.149192, 30.316078);
        Shop s2 = shop(2L, 1L, 120.15, 30.32);
        Shop s3 = shop(3L, 2L, 121.0, 31.0);
        Shop incomplete = shop(4L, 1L, null, null);
        when(shopMapper.selectList(null)).thenReturn(Arrays.asList(s1, s2, s3, incomplete));

        service.rebuildFromDatabase();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> deleted = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).delete(deleted.capture());
        assertThat(deleted.getValue()).containsExactly("merchant:shop:geo:1", "merchant:shop:geo:9");
        // namespace cleanup must never delete other merchant keys
        assertThat(deleted.getValue()).allMatch(k -> k.startsWith("merchant:shop:geo:"));

        verify(geoOps).add("merchant:shop:geo:1", new Point(120.149192, 30.316078), "1");
        verify(geoOps).add("merchant:shop:geo:1", new Point(120.15, 30.32), "2");
        verify(geoOps).add("merchant:shop:geo:2", new Point(121.0, 31.0), "3");
        verify(geoOps, never()).add(anyString(), any(Point.class), eq("4"));
    }

    @Test
    void rebuildWithoutStaleKeysSkipsDelete() {
        stubScan();
        when(shopMapper.selectList(null)).thenReturn(List.of());

        service.rebuildFromDatabase();

        verify(redisTemplate, never()).delete(any(List.class));
    }

    @Test
    void rebuildFailurePropagatesToFailStartup() {
        stubScan();
        Shop s1 = shop(1L, 1L, 120.1, 30.1);
        when(shopMapper.selectList(null)).thenReturn(List.of(s1));
        doThrow(new RuntimeException("redis down")).when(geoOps)
                .add(eq("merchant:shop:geo:1"), any(Point.class), eq("1"));

        assertThatThrownBy(() -> service.rebuildFromDatabase())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("redis down");
    }

    @Test
    void addShopWritesMemberWithFinalCoordinates() {
        Shop s1 = shop(1L, 1L, 120.149192, 30.316078);

        service.addShop(s1);

        verify(geoOps).add("merchant:shop:geo:1", new Point(120.149192, 30.316078), "1");
    }

    @Test
    void addShopNullIsNoOp() {
        service.addShop(null);
        verify(geoOps, never()).add(anyString(), any(Point.class), anyString());
    }

    @Test
    void updateSameTypeSameCoordinatesIsNoOp() {
        Shop before = shop(1L, 1L, 120.1, 30.1);
        Shop after = shop(1L, 1L, 120.1, 30.1);

        service.updateAfterCommit(before, after);

        verify(geoOps, never()).remove(anyString(), any());
        verify(geoOps, never()).add(anyString(), any(Point.class), anyString());
    }

    @Test
    void updateCoordinatesOnlyReaddsToSameType() {
        Shop before = shop(1L, 1L, 120.1, 30.1);
        Shop after = shop(1L, 1L, 120.2, 30.2);

        service.updateAfterCommit(before, after);

        verify(geoOps, never()).remove(anyString(), any());
        verify(geoOps).add("merchant:shop:geo:1", new Point(120.2, 30.2), "1");
    }

    @Test
    void updateTypeRemovesOldTypeAndAddsNewType() {
        Shop before = shop(1L, 1L, 120.1, 30.1);
        Shop after = shop(1L, 2L, 120.1, 30.1);

        service.updateAfterCommit(before, after);

        verify(geoOps).remove("merchant:shop:geo:1", "1");
        verify(geoOps).add("merchant:shop:geo:2", new Point(120.1, 30.1), "1");
    }

    @Test
    void updateTypeAndCoordinatesRemovesOldTypeAndAddsFinal() {
        Shop before = shop(1L, 1L, 120.1, 30.1);
        Shop after = shop(1L, 2L, 121.5, 31.5);

        service.updateAfterCommit(before, after);

        verify(geoOps).remove("merchant:shop:geo:1", "1");
        verify(geoOps).add("merchant:shop:geo:2", new Point(121.5, 31.5), "1");
    }

    @Test
    void updateNullStatesAreNoOp() {
        service.updateAfterCommit(null, shop(1L, 1L, 120.1, 30.1));
        service.updateAfterCommit(shop(1L, 1L, 120.1, 30.1), null);
        verify(geoOps, never()).remove(anyString(), any());
        verify(geoOps, never()).add(anyString(), any(Point.class), anyString());
    }
}
