package com.linklife.trade.lifecycle.integration;

import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.trade.lifecycle.outbox.OrderCloseCompensationCommand;
import com.linklife.trade.lifecycle.outbox.OrderCloseCompensationResult;
import com.linklife.trade.lifecycle.outbox.RedisOrderCloseCompensationAdapter;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 017G 手工集成：真实 Redis 上的订单关闭补偿 Lua 首次/幂等/异常 Key/marker/非法参数。
 *
 * <p>默认不进 Surefire；显式运行需设置专用隔离环境变量且 schema 为 linklife_it_017g_*。</p>
 */
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_INTEGRATION_ENABLED", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_CONFIRM_ISOLATED", matches = "(?i)true")
@ManualIntegrationEnvironment.FullIsolationRequired
  @SpringBootTest(classes = com.linklife.transaction.TransactionServiceApplication.class)
class OrderCloseRedisCompensationManualIntegration extends Stage3E017gIntegrationSupport {

    @Resource
    private RedisOrderCloseCompensationAdapter compensationAdapter;

    private final List<String> createdKeys = new ArrayList<>();

    @AfterEach
    void cleanup() {
        if (!createdKeys.isEmpty()) {
            stringRedisTemplate.delete(createdKeys);
        }
        createdKeys.clear();
    }

    private void registerKeys(long orderId, long voucherId) {
        createdKeys.add(stockKey(voucherId));
        createdKeys.add(markerKey(orderId));
        createdKeys.add(orderSetKey(voucherId));
    }

    private OrderCloseCompensationCommand command(long orderId, long userId, long voucherId, String eventId) {
        return new OrderCloseCompensationCommand(
                orderId, userId, voucherId, eventId,
                "VOUCHER_ORDER:CLOSED:" + orderId + ":V1", 1,
                LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS));
    }

    private String markerField(long orderId, String field) {
        Object value = stringRedisTemplate.opsForHash().get(markerKey(orderId), field);
        return value == null ? null : String.valueOf(value);
    }

    private void assertMarkerComplete(long orderId, long userId, long voucherId, String eventId, String businessKey) {
        assertThat(markerField(orderId, "state")).isEqualTo("done");
        assertThat(markerField(orderId, "eventId")).isEqualTo(eventId);
        assertThat(markerField(orderId, "businessKey")).isEqualTo(businessKey);
        assertThat(markerField(orderId, "orderId")).isEqualTo(String.valueOf(orderId));
        assertThat(markerField(orderId, "userId")).isEqualTo(String.valueOf(userId));
        assertThat(markerField(orderId, "voucherId")).isEqualTo(String.valueOf(voucherId));
        assertThat(markerField(orderId, "handledAt")).isNotNull().isNotBlank();
        assertThat(markerField(orderId, "eventVersion")).isEqualTo("1");
    }

    @Test
    void firstApplyIncrementsStockWritesCompleteMarkerWithoutTtlAndKeepsEligibilitySet() {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2001L;
        registerKeys(orderId, voucherId);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "10");
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
        String eventId = "evt-" + UUID.randomUUID();

        OrderCloseCompensationResult result =
                compensationAdapter.compensate(command(orderId, userId, voucherId, eventId));

        assertThat(result.outcome()).isEqualTo(OrderCloseCompensationResult.CompensationOutcome.SUCCESS);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("11");
        assertMarkerComplete(orderId, userId, voucherId, eventId, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1");
        assertThat(stringRedisTemplate.getExpire(markerKey(orderId))).isEqualTo(-1L);
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId)))
                .containsExactly(String.valueOf(userId));
    }

    @Test
    void sameIdentityRetryIsIdempotentAndKeepsFirstHandledAt() {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2002L;
        registerKeys(orderId, voucherId);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "5");
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
        String eventId = "evt-" + UUID.randomUUID();

        OrderCloseCompensationCommand first = command(orderId, userId, voucherId, eventId);
        assertThat(compensationAdapter.compensate(first).outcome())
                .isEqualTo(OrderCloseCompensationResult.CompensationOutcome.SUCCESS);
        String firstHandledAt = markerField(orderId, "handledAt");
        assertThat(firstHandledAt).isNotNull();

        // 模拟“客户端未确认/网络不确定后的持久状态”：不推进任何状态，直接再次补偿
        OrderCloseCompensationResult retry = compensationAdapter.compensate(
                command(orderId, userId, voucherId, eventId));

        assertThat(retry.outcome()).isEqualTo(OrderCloseCompensationResult.CompensationOutcome.SUCCESS);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("6");
        assertThat(markerField(orderId, "handledAt")).isEqualTo(firstHandledAt);
        assertThat(stringRedisTemplate.getExpire(markerKey(orderId))).isEqualTo(-1L);
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId)))
                .containsExactly(String.valueOf(userId));
    }

    @Test
    void networkUncertainPersistentStateEquivalentDoesNotDoubleIncrement() {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2003L;
        registerKeys(orderId, voucherId);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "100");
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
        String eventId = "evt-" + UUID.randomUUID();

        // 正式 Lua 执行成功（等价于客户端断网前 Redis 已提交）
        assertThat(compensationAdapter.compensate(command(orderId, userId, voucherId, eventId)).outcome())
                .isEqualTo(OrderCloseCompensationResult.CompensationOutcome.SUCCESS);
        // 故意不推进 Outbox SUCCESS，再通过正式适配器重试
        assertThat(compensationAdapter.compensate(command(orderId, userId, voucherId, eventId)).outcome())
                .isEqualTo(OrderCloseCompensationResult.CompensationOutcome.SUCCESS);

        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("101");
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId)))
                .containsExactly(String.valueOf(userId));
    }

    @Test
    void stockKeyMissingIsFatalAndDoesNotWriteMarker() {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2004L;
        registerKeys(orderId, voucherId);
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));

        OrderCloseCompensationResult result = compensationAdapter.compensate(
                command(orderId, userId, voucherId, "evt-" + UUID.randomUUID()));

        assertThat(result).isEqualTo(OrderCloseCompensationResult.fatal("REDIS_STOCK_KEY_MISSING"));
        assertThat(stringRedisTemplate.hasKey(markerKey(orderId))).isFalse();
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId)))
                .containsExactly(String.valueOf(userId));
    }

    @Test
    void stockKeyWrongTypeIsFatal() {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2005L;
        registerKeys(orderId, voucherId);
        stringRedisTemplate.opsForList().rightPush(stockKey(voucherId), "1");

        OrderCloseCompensationResult result = compensationAdapter.compensate(
                command(orderId, userId, voucherId, "evt-" + UUID.randomUUID()));

        assertThat(result).isEqualTo(OrderCloseCompensationResult.fatal("REDIS_STOCK_KEY_TYPE_INVALID"));
        assertThat(stringRedisTemplate.hasKey(markerKey(orderId))).isFalse();
    }

    @Test
    void nonCanonicalStockValuesAreFatalAndStockUnchanged() {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2006L;
        registerKeys(orderId, voucherId);

        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "-1");
        assertThat(compensationAdapter.compensate(command(orderId, userId, voucherId, "evt-" + UUID.randomUUID())))
                .isEqualTo(OrderCloseCompensationResult.fatal("REDIS_STOCK_VALUE_INVALID"));
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("-1");
        assertThat(stringRedisTemplate.hasKey(markerKey(orderId))).isFalse();

        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "1.5");
        assertThat(compensationAdapter.compensate(command(orderId, userId, voucherId, "evt-" + UUID.randomUUID())))
                .isEqualTo(OrderCloseCompensationResult.fatal("REDIS_STOCK_VALUE_INVALID"));
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("1.5");
    }

    @Test
    void markerKeyWrongTypeIsFatalAndStockUnchanged() {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2007L;
        registerKeys(orderId, voucherId);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "8");
        stringRedisTemplate.opsForValue().set(markerKey(orderId), "not-a-hash");

        OrderCloseCompensationResult result = compensationAdapter.compensate(
                command(orderId, userId, voucherId, "evt-" + UUID.randomUUID()));

        assertThat(result).isEqualTo(OrderCloseCompensationResult.fatal("REDIS_MARKER_KEY_TYPE_INVALID"));
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("8");
    }

    @Test
    void markerMissingHandledAtIsCorruptAndStockUnchanged() {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2008L;
        registerKeys(orderId, voucherId);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "12");
        String businessKey = "VOUCHER_ORDER:CLOSED:" + orderId + ":V1";
        stringRedisTemplate.opsForHash().putAll(markerKey(orderId), Map.of(
                "state", "done",
                "eventId", "evt-x",
                "businessKey", businessKey,
                "orderId", String.valueOf(orderId),
                "userId", String.valueOf(userId),
                "voucherId", String.valueOf(voucherId),
                "eventVersion", "1"));

        OrderCloseCompensationResult result = compensationAdapter.compensate(
                command(orderId, userId, voucherId, "evt-x"));

        assertThat(result).isEqualTo(OrderCloseCompensationResult.fatal("REDIS_MARKER_CORRUPT"));
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("12");
    }

    @Test
    void markerAbnormalStateIsCorruptAndStockUnchanged() {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2009L;
        registerKeys(orderId, voucherId);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "3");
        String businessKey = "VOUCHER_ORDER:CLOSED:" + orderId + ":V1";
        stringRedisTemplate.opsForHash().putAll(markerKey(orderId), Map.of(
                "state", "open",
                "eventId", "evt-y",
                "businessKey", businessKey,
                "orderId", String.valueOf(orderId),
                "userId", String.valueOf(userId),
                "voucherId", String.valueOf(voucherId),
                "handledAt", "2026-08-06T10:00:00",
                "eventVersion", "1"));

        assertThat(compensationAdapter.compensate(command(orderId, userId, voucherId, "evt-y")))
                .isEqualTo(OrderCloseCompensationResult.fatal("REDIS_MARKER_CORRUPT"));
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("3");
    }

    @Test
    void markerIdentityConflictIsFatalAndStockUnchanged() {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2010L;
        registerKeys(orderId, voucherId);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "6");
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
        String businessKey = "VOUCHER_ORDER:CLOSED:" + orderId + ":V1";
        stringRedisTemplate.opsForHash().putAll(markerKey(orderId), Map.of(
                "state", "done",
                "eventId", "evt-original",
                "businessKey", businessKey,
                "orderId", String.valueOf(orderId),
                "userId", String.valueOf(userId),
                "voucherId", String.valueOf(voucherId),
                "handledAt", "2026-08-06T10:00:00",
                "eventVersion", "1"));

        // 新命令携带不同 eventId → 身份冲突
        assertThat(compensationAdapter.compensate(command(orderId, userId, voucherId, "evt-different")))
                .isEqualTo(OrderCloseCompensationResult.fatal("REDIS_MARKER_IDENTITY_CONFLICT"));
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("6");
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId)))
                .containsExactly(String.valueOf(userId));
    }

    @Test
    void invalidArgumentsRejectedByDirectEvalOfOfficialLua() throws Exception {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = 2011L;
        registerKeys(orderId, voucherId);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "4");
        String lua = new String(new ClassPathResource("order-close-compensation.lua")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        DefaultRedisScript<Long> script = new DefaultRedisScript<>(lua, Long.class);
        List<String> keys = List.of(stockKey(voucherId), markerKey(orderId));
        String[] validArgs = {String.valueOf(orderId), String.valueOf(userId), String.valueOf(voucherId),
                "evt-z", "VOUCHER_ORDER:CLOSED:" + orderId + ":V1", "2026-08-06T10:00:00", "1"};

        // 非规范 ID "1.5" → INVALID_ARGUMENT(10)
        String[] nonCanonicalId = validArgs.clone();
        nonCanonicalId[0] = "1.5";
        assertThat(stringRedisTemplate.execute(script, keys, (Object[]) nonCanonicalId)).isEqualTo(10L);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("4");

        // 错误 ARGV 数量（6 个）→ INVALID_ARGUMENT(10)
        String[] shortArgs = {validArgs[0], validArgs[1], validArgs[2], validArgs[3], validArgs[4], validArgs[5]};
        assertThat(stringRedisTemplate.execute(script, keys, (Object[]) shortArgs)).isEqualTo(10L);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("4");

        // 合法参数正常 APPLIED(0)
        assertThat(stringRedisTemplate.execute(script, keys, (Object[]) validArgs)).isEqualTo(0L);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("5");
    }
}
