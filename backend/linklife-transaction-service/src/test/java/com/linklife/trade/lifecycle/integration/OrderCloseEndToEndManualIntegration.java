package com.linklife.trade.lifecycle.integration;

import com.linklife.common.core.context.UserContext;
import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.trade.application.OrderLifecycleService;
import com.linklife.trade.application.OrderTimeoutCloseService;
import com.linklife.trade.application.OutboxPollingService;
import com.linklife.trade.lifecycle.outbox.OrderCloseCompensationCommand;
import com.linklife.trade.lifecycle.outbox.OrderCloseCompensationResult;
import com.linklife.trade.lifecycle.outbox.OutboxEventRouter;
import com.linklife.trade.lifecycle.outbox.OutboxPollResult;
import com.linklife.trade.lifecycle.outbox.OutboxProperties;
import com.linklife.trade.lifecycle.outbox.RedisOrderCloseCompensationAdapter;
import com.linklife.trade.mapper.OutboxEventMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 017G 手工集成：订单关闭 → MySQL 库存恢复 → 状态日志/Outbox → Outbox 处理 → Redis 库存恢复 → marker → Outbox SUCCESS。
 *
 * <p>覆盖用户取消与超时关闭两个入口；默认不进 Surefire；显式运行需设置专用隔离环境变量
 * 且 schema 为 linklife_it_017g_*。</p>
 */
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_INTEGRATION_ENABLED", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_CONFIRM_ISOLATED", matches = "(?i)true")
@ManualIntegrationEnvironment.FullIsolationRequired
  @SpringBootTest(classes = com.linklife.transaction.TransactionServiceApplication.class, properties = {
        "linklife.trade.outbox.enabled=true",
        "linklife.trade.outbox.initial-delay-ms=600000",
        "linklife.trade.outbox.scan-delay-ms=600000"})
class OrderCloseEndToEndManualIntegration extends Stage3E017gIntegrationSupport {

    @Resource
    private OrderLifecycleService orderLifecycleService;

    @Resource
    private OrderTimeoutCloseService orderTimeoutCloseService;

    @Resource
    private OutboxEventMapper outboxEventMapper;

    @Resource
    private OutboxProperties outboxProperties;

    @Resource
    private OutboxEventRouter outboxEventRouter;

    @Resource
    private RedisOrderCloseCompensationAdapter compensationAdapter;

    private final List<long[]> createdRows = new ArrayList<>();

    @AfterEach
    void cleanup() {
        UserContext.clear();
        for (long[] pair : createdRows) {
            stringRedisTemplate.delete(stockKey(pair[1]));
            stringRedisTemplate.delete(markerKey(pair[0]));
            stringRedisTemplate.delete(orderSetKey(pair[1]));
            cleanupOrder(pair[0], pair[1]);
        }
        createdRows.clear();
    }

    private OutboxPollingService newPoller() {
        OutboxPollingService poller = new OutboxPollingService();
        ReflectionTestUtils.setField(poller, "outboxEventMapper", outboxEventMapper);
        ReflectionTestUtils.setField(poller, "outboxProperties", outboxProperties);
        ReflectionTestUtils.setField(poller, "outboxEventHandler", outboxEventRouter);
        return poller;
    }

    /**
     * 初始 PENDING 的 next_retry_time 可能带亚秒精度，而 poller 的 scanNow 截断到秒；
     * 有界重试（最多 6 次、间隔 500ms）保证在真实秒边界上确定性处理。
     */
    private OutboxPollResult pollUntilSuccess() {
        OutboxPollResult last = null;
        for (int i = 0; i < 6; i++) {
            last = newPoller().pollDueEvents();
            if (last.succeeded() >= 1) {
                return last;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return last;
    }

    private void assertFullChainFinalState(long orderId, long userId, long voucherId,
                                           int mysqlStock, int redisStock) {
        assertThat(orderStatus(orderId)).isEqualTo(4);
        assertThat(seckillStock(voucherId)).isEqualTo(mysqlStock + 1);
        assertThat(statusLogCount(orderId)).isEqualTo(1);
        assertThat(outboxCount(orderId)).isEqualTo(1);
        assertThat(outboxStatus(orderId)).isEqualTo("SUCCESS");
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId)))
                .isEqualTo(String.valueOf(redisStock + 1));
        assertThat(stringRedisTemplate.opsForHash().get(markerKey(orderId), "state")).isEqualTo("done");
        assertThat(stringRedisTemplate.opsForHash().get(markerKey(orderId), "handledAt")).isNotNull();
        assertThat(stringRedisTemplate.getExpire(markerKey(orderId))).isEqualTo(-1L);
        assertThat(stringRedisTemplate.opsForSet().members(orderSetKey(voucherId)))
                .containsExactly(String.valueOf(userId));
    }

    @Test
    void userCancelEndToEndFullChain() {
        long voucherId = nextVoucherId();
        long userId = 3001L;
        int mysqlStock = 5;
        int redisStock = 8;
        insertSeckillVoucher(voucherId, mysqlStock);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), String.valueOf(redisStock));
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
        long orderId = nextOrderId();
        insertOrder(orderId, userId, voucherId, 1, LocalDateTime.now().minusMinutes(10));
        createdRows.add(new long[]{orderId, voucherId});

        UserContext.set(userId);
        try {
            assertThat(orderLifecycleService.cancelByCurrentUser(orderId)).isEqualTo(orderId);
        } finally {
            UserContext.clear();
        }

        assertThat(outboxStatus(orderId)).isEqualTo("PENDING");
        OutboxPollResult pollResult = pollUntilSuccess();
        assertThat(pollResult.succeeded()).isEqualTo(1);
        assertFullChainFinalState(orderId, userId, voucherId, mysqlStock, redisStock);

        // 同一事件（相同 eventId）重跑不重复 +1
        String originalEventId = (String) stringRedisTemplate.opsForHash().get(markerKey(orderId), "eventId");
        assertThat(originalEventId).isNotNull();
        assertThat(compensationAdapter.compensate(
                new OrderCloseCompensationCommand(orderId, userId, voucherId, originalEventId,
                        "VOUCHER_ORDER:CLOSED:" + orderId + ":V1", 1,
                        LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))).outcome())
                .isEqualTo(OrderCloseCompensationResult.CompensationOutcome.SUCCESS);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId)))
                .isEqualTo(String.valueOf(redisStock + 1));
    }

    @Test
    void timeoutCloseEndToEndFullChain() {
        long voucherId = nextVoucherId();
        long userId = 3002L;
        int mysqlStock = 3;
        int redisStock = 6;
        insertSeckillVoucher(voucherId, mysqlStock);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), String.valueOf(redisStock));
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
        long orderId = nextOrderId();
        insertOrder(orderId, userId, voucherId, 1, LocalDateTime.now().minusMinutes(30));
        createdRows.add(new long[]{orderId, voucherId});

        assertThat(orderTimeoutCloseService.closeExpiredOrders().closed()).isEqualTo(1);
        assertThat(outboxStatus(orderId)).isEqualTo("PENDING");

        OutboxPollResult pollResult = pollUntilSuccess();
        assertThat(pollResult.succeeded()).isEqualTo(1);
        assertFullChainFinalState(orderId, userId, voucherId, mysqlStock, redisStock);

        String originalEventId = (String) stringRedisTemplate.opsForHash().get(markerKey(orderId), "eventId");
        assertThat(originalEventId).isNotNull();
        assertThat(compensationAdapter.compensate(
                new OrderCloseCompensationCommand(orderId, userId, voucherId, originalEventId,
                        "VOUCHER_ORDER:CLOSED:" + orderId + ":V1", 1,
                        LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS))).outcome())
                .isEqualTo(OrderCloseCompensationResult.CompensationOutcome.SUCCESS);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId)))
                .isEqualTo(String.valueOf(redisStock + 1));
    }
}
