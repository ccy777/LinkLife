package com.linklife.trade.lifecycle.integration;

import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.trade.admission.RedisSeckillAdmissionAdapter;
import com.linklife.trade.admission.SeckillAdmissionDecision;
import com.linklife.trade.application.VoucherOrderTransactionalService;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.submission.OrderCreateFailureCompensationAdapter;
import com.linklife.trade.submission.OrderCreateFailureCompensationCommand;
import com.linklife.trade.submission.OrderCreateCompensationMode;
import com.linklife.trade.submission.OrderCreationFailureDecision;
import com.linklife.trade.submission.OrderCreationFailureService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 017J-A 手工集成：真实隔离 MySQL/Redis 上验证三种 MySQL 事实分类、
 * 两种订单创建失败补偿模式（释放资格/保留资格）、首次与重复幂等、响应丢失等价重试，
 * 以及 seckill Lua 失败后无孤儿 Stream 消息。
 *
 * <p>默认不进 Surefire；显式运行需设置专用隔离环境变量且 schema 为 linklife_it_017g_*。</p>
 */
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_INTEGRATION_ENABLED", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_CONFIRM_ISOLATED", matches = "(?i)true")
@ManualIntegrationEnvironment.FullIsolationRequired
  @SpringBootTest(classes = com.linklife.transaction.TransactionServiceApplication.class)
class OrderCreateTerminalConsistencyManualIntegration extends Stage3E017gIntegrationSupport {

    @Resource
    private VoucherOrderTransactionalService voucherOrderTransactionalService;

    @Resource
    private OrderCreationFailureService orderCreationFailureService;

    @Resource
    private OrderCreateFailureCompensationAdapter compensationAdapter;

    @Resource
    private RedisSeckillAdmissionAdapter admissionAdapter;

    private final List<long[]> createdRows = new ArrayList<>();
    private final List<String> createdRedisKeys = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (long[] pair : createdRows) {
            cleanupOrder(pair[0], pair[1]);
            stringRedisTemplate.delete(stockKey(pair[1]));
            stringRedisTemplate.delete(orderSetKey(pair[1]));
            stringRedisTemplate.delete(createMarkerKey(pair[0]));
            stringRedisTemplate.delete("transaction:seckill:begin:" + pair[1]);
            stringRedisTemplate.delete("transaction:seckill:end:" + pair[1]);
            stringRedisTemplate.delete("transaction:order:submission:" + pair[0]);
        }
        if (!createdRedisKeys.isEmpty()) {
            stringRedisTemplate.delete(createdRedisKeys);
        }
        createdRows.clear();
        createdRedisKeys.clear();
    }

    private VoucherOrder message(long orderId, long userId, long voucherId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        return order;
    }

    private String markerField(long orderId, String field) {
        Object value = stringRedisTemplate.opsForHash().get(createMarkerKey(orderId), field);
        return value == null ? null : String.valueOf(value);
    }

    private String createMarkerKey(long orderId) {
        return "transaction:order:create:comp:" + orderId;
    }

    private void assertMarkerComplete(long orderId, long userId, long voucherId,
                                      OrderCreateCompensationMode mode, long existingOrderId) {
        assertThat(markerField(orderId, "state")).isEqualTo("done");
        assertThat(markerField(orderId, "orderId")).isEqualTo(String.valueOf(orderId));
        assertThat(markerField(orderId, "userId")).isEqualTo(String.valueOf(userId));
        assertThat(markerField(orderId, "voucherId")).isEqualTo(String.valueOf(voucherId));
        assertThat(markerField(orderId, "mode")).isEqualTo(mode.name());
        assertThat(markerField(orderId, "existingOrderId")).isEqualTo(String.valueOf(existingOrderId));
        assertThat(markerField(orderId, "handledAt")).isNotNull().isNotBlank();
        assertThat(markerField(orderId, "version")).isEqualTo("1");
        assertThat(stringRedisTemplate.getExpire(createMarkerKey(orderId))).isEqualTo(-1L);
    }

    @Test
    void conflictingOtherOrderRestoresStockAndKeepsQualificationFirstAndRepeat() {
        long voucherId = nextVoucherId();
        long userId = 4001L;
        int stock = 10;
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), String.valueOf(stock));
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
        long existingOrderId = nextOrderId();
        insertOrder(existingOrderId, userId, voucherId, 1, LocalDateTime.now().minusMinutes(5));
        long messageOrderId = nextOrderId();
        createdRows.add(new long[]{existingOrderId, voucherId});
        createdRows.add(new long[]{messageOrderId, voucherId});

        OrderCreationFailureDecision decision =
                orderCreationFailureService.classifyAndCompensate(message(messageOrderId, userId, voucherId));

        assertThat(decision.type()).isEqualTo(OrderCreationFailureDecision.DecisionType.CONFLICTING_OTHER_ORDER);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo(String.valueOf(stock + 1));
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId)))
                .containsExactly(String.valueOf(userId));
        assertMarkerComplete(messageOrderId, userId, voucherId,
                OrderCreateCompensationMode.RESTORE_STOCK_KEEP_QUALIFICATION, existingOrderId);

        // 响应丢失等价重试：同消息再次终态分类，库存不重复 +1
        OrderCreationFailureDecision retry =
                orderCreationFailureService.classifyAndCompensate(message(messageOrderId, userId, voucherId));
        assertThat(retry.type()).isEqualTo(OrderCreationFailureDecision.DecisionType.CONFLICTING_OTHER_ORDER);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo(String.valueOf(stock + 1));
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId)))
                .containsExactly(String.valueOf(userId));
    }

    @Test
    void noMySqlOrderRestoresStockAndReleasesQualificationFirstAndRepeat() {
        long voucherId = nextVoucherId();
        long userId = 4002L;
        int stock = 5;
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), String.valueOf(stock));
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
        long messageOrderId = nextOrderId();
        createdRows.add(new long[]{messageOrderId, voucherId});

        OrderCreationFailureDecision decision =
                orderCreationFailureService.classifyAndCompensate(message(messageOrderId, userId, voucherId));

        assertThat(decision.type()).isEqualTo(OrderCreationFailureDecision.DecisionType.NO_MYSQL_ORDER);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo(String.valueOf(stock + 1));
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId))).isEmpty();
        assertMarkerComplete(messageOrderId, userId, voucherId,
                OrderCreateCompensationMode.RESTORE_STOCK_AND_RELEASE_QUALIFICATION, 0L);

        OrderCreationFailureDecision retry =
                orderCreationFailureService.classifyAndCompensate(message(messageOrderId, userId, voucherId));
        assertThat(retry.type()).isEqualTo(OrderCreationFailureDecision.DecisionType.NO_MYSQL_ORDER);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo(String.valueOf(stock + 1));
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId))).isEmpty();
    }

    @Test
    void releaseModeWhenQualificationInitiallyAbsentDoesNotCreateMember() {
        long voucherId = nextVoucherId();
        long userId = 4006L;
        int stock = 8;
        // 资格 Set 原本不存在（成员缺失），写前 membership=0
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), String.valueOf(stock));
        long messageOrderId = nextOrderId();
        createdRows.add(new long[]{messageOrderId, voucherId});

        OrderCreationFailureDecision decision =
                orderCreationFailureService.classifyAndCompensate(message(messageOrderId, userId, voucherId));

        assertThat(decision.type()).isEqualTo(OrderCreationFailureDecision.DecisionType.NO_MYSQL_ORDER);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo(String.valueOf(stock + 1));
        // 正常路径不得凭空创建资格：membership 仍为 0
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId))).isEmpty();
        assertMarkerComplete(messageOrderId, userId, voucherId,
                OrderCreateCompensationMode.RESTORE_STOCK_AND_RELEASE_QUALIFICATION, 0L);

        OrderCreationFailureDecision retry =
                orderCreationFailureService.classifyAndCompensate(message(messageOrderId, userId, voucherId));
        assertThat(retry.type()).isEqualTo(OrderCreationFailureDecision.DecisionType.NO_MYSQL_ORDER);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo(String.valueOf(stock + 1));
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId))).isEmpty();
    }

    @Test
    void currentOrderPersistedDoesNotCompensate() {
        long voucherId = nextVoucherId();
        long userId = 4003L;
        int stock = 7;
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), String.valueOf(stock));
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
        long messageOrderId = nextOrderId();
        insertOrder(messageOrderId, userId, voucherId, 1, LocalDateTime.now().minusMinutes(5));
        createdRows.add(new long[]{messageOrderId, voucherId});

        OrderCreationFailureDecision decision =
                orderCreationFailureService.classifyAndCompensate(message(messageOrderId, userId, voucherId));

        assertThat(decision.type()).isEqualTo(OrderCreationFailureDecision.DecisionType.CURRENT_ORDER_PERSISTED);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo(String.valueOf(stock));
        assertThat(stringRedisTemplate.hasKey(createMarkerKey(messageOrderId))).isFalse();
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId)))
                .containsExactly(String.valueOf(userId));
    }

    @Test
    void transactionalServiceClassifiesThreeMySqlFactsPrecisely() {
        long voucherId = nextVoucherId();
        long userId = 4004L;
        insertSeckillVoucher(voucherId, 100);
        long firstOrderId = nextOrderId();
        createdRows.add(new long[]{firstOrderId, voucherId});

        // 1. 无订单 → CREATED
        VoucherOrderTransactionalService.ProcessResult created =
                voucherOrderTransactionalService.process(message(firstOrderId, userId, voucherId));
        assertThat(created).isEqualTo(VoucherOrderTransactionalService.ProcessResult.CREATED);
        assertThat(seckillStock(voucherId)).isEqualTo(99);

        // 2. 相同 orderId → IDEMPOTENT_SAME_ORDER，不重复扣库存
        VoucherOrderTransactionalService.ProcessResult same =
                voucherOrderTransactionalService.process(message(firstOrderId, userId, voucherId));
        assertThat(same).isEqualTo(VoucherOrderTransactionalService.ProcessResult.IDEMPOTENT_SAME_ORDER);
        assertThat(seckillStock(voucherId)).isEqualTo(99);

        // 3. 不同 orderId（同 user/voucher）→ CONFLICTING_EXISTING_ORDER，不扣库存、不落库
        long secondOrderId = nextOrderId();
        createdRows.add(new long[]{secondOrderId, voucherId});
        VoucherOrderTransactionalService.ProcessResult conflicting =
                voucherOrderTransactionalService.process(message(secondOrderId, userId, voucherId));
        assertThat(conflicting).isEqualTo(VoucherOrderTransactionalService.ProcessResult.CONFLICTING_EXISTING_ORDER);
        assertThat(seckillStock(voucherId)).isEqualTo(99);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher_order WHERE id = ?", Integer.class, secondOrderId);
        assertThat(count).isZero();
    }

    @Test
    void seckillLuaFailureLeavesNoOrphanStreamMessage() {
        long voucherId = nextVoucherId();
        long userId = 4005L;
        long orderId = nextOrderId();
        int stock = 20;
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), String.valueOf(stock));
        stringRedisTemplate.opsForValue().set("transaction:seckill:begin:" + voucherId,
                String.valueOf(System.currentTimeMillis() - 60_000L));
        stringRedisTemplate.opsForValue().set("transaction:seckill:end:" + voucherId,
                String.valueOf(System.currentTimeMillis() + 600_000L));
        createdRedisKeys.add(stockKey(voucherId));
        createdRedisKeys.add(orderSetKey(voucherId));
        createdRedisKeys.add("transaction:seckill:begin:" + voucherId);
        createdRedisKeys.add("transaction:seckill:end:" + voucherId);
        createdRedisKeys.add("transaction:order:submission:" + orderId);

        // 提交状态 Key 已存在（异常残留）→ 准入返回 UNAVAILABLE，任何写操作前拒绝
        stringRedisTemplate.opsForHash().put("transaction:order:submission:" + orderId, "state", "ACCEPTED");
        SeckillAdmissionDecision decision = admissionAdapter.admit(
                voucherId, userId, orderId, System.currentTimeMillis(), 86_400L);

        assertThat(decision).isEqualTo(SeckillAdmissionDecision.UNAVAILABLE);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo(String.valueOf(stock));
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId))).isEmpty();
        Long streamLength = stringRedisTemplate.opsForStream().size("transaction:stream.orders");
        assertThat(streamLength).as("seckill Lua 失败后不得留下可消费 Stream 消息").isZero();
    }
}
