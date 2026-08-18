package com.linklife.trade.lifecycle.integration;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.trade.application.OutboxPollingService;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.lifecycle.outbox.OutboxEventHandler;
import com.linklife.trade.lifecycle.outbox.OutboxEventStatus;
import com.linklife.trade.lifecycle.outbox.OutboxHandleResult;
import com.linklife.trade.lifecycle.outbox.OutboxPollResult;
import com.linklife.trade.lifecycle.outbox.OutboxProperties;
import com.linklife.trade.mapper.OutboxEventMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 017G 手工集成：Outbox 真实 MySQL 租约与多实例竞争（PENDING 双实例领取、租约过期回收、旧 token 不覆盖）。
 *
 * <p>默认不进 Surefire；显式运行需设置专用隔离环境变量且 schema 为 linklife_it_017g_*。</p>
 */
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_INTEGRATION_ENABLED", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_CONFIRM_ISOLATED", matches = "(?i)true")
@ManualIntegrationEnvironment.FullIsolationRequired
  @SpringBootTest(classes = com.linklife.transaction.TransactionServiceApplication.class)
class OutboxLeaseMySqlManualIntegration extends Stage3E017gIntegrationSupport {

    @Resource
    private OutboxEventMapper outboxEventMapper;

    private final List<String> createdEventIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String eventId : createdEventIds) {
            jdbcTemplate.update("DELETE FROM tb_outbox_event WHERE event_id = ?", eventId);
        }
        createdEventIds.clear();
    }

    private OutboxProperties newProperties(int leaseSeconds) {
        OutboxProperties properties = new OutboxProperties();
        properties.setBatchSize(10);
        properties.setMaxBatchesPerRun(5);
        properties.setLeaseSeconds(leaseSeconds);
        properties.setMaxRetries(5);
        properties.setRetryBaseDelayMs(1000L);
        properties.setRetryMaxDelayMs(60_000L);
        return properties;
    }

    private OutboxPollingService newPoller(OutboxProperties properties, OutboxEventHandler handler) {
        OutboxPollingService poller = new OutboxPollingService();
        ReflectionTestUtils.setField(poller, "outboxEventMapper", outboxEventMapper);
        ReflectionTestUtils.setField(poller, "outboxProperties", properties);
        ReflectionTestUtils.setField(poller, "outboxEventHandler", handler);
        return poller;
    }

    private long insertOutbox(String status, int retryCount, LocalDateTime nextRetryTime,
                              String lockToken, LocalDateTime lockedUntil) {
        long orderId = nextOrderId();
        String eventId = "it-" + UUID.randomUUID();
        String businessKey = "VOUCHER_ORDER:CLOSED:" + orderId + ":V1";
        jdbcTemplate.update("INSERT INTO tb_outbox_event "
                        + "(event_id, business_key, aggregate_type, aggregate_id, event_type, event_version, "
                        + "payload, status, retry_count, next_retry_time, lock_token, locked_until, "
                        + "processing_started_time, created_time, updated_time) "
                        + "VALUES (?, ?, 'VOUCHER_ORDER', ?, 'ORDER_CLOSED', 1, '{}', ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                eventId, businessKey, orderId, status, retryCount, nextRetryTime,
                lockToken, lockedUntil, lockedUntil);
        createdEventIds.add(eventId);
        return orderId;
    }

    private Long rowIdByBusinessKey(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tb_outbox_event WHERE business_key = ?",
                Long.class, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1");
    }

    static final class CountingHandler implements OutboxEventHandler {
        final AtomicInteger invoked = new AtomicInteger();
        final long delayMillis;

        CountingHandler(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public OutboxHandleResult handle(OutboxEvent event) {
            invoked.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return OutboxHandleResult.success();
        }
    }

    @Test
    void twoPollersCompeteForOnePendingEvent() throws Exception {
        long orderId = insertOutbox("PENDING", 0, LocalDateTime.now().minusSeconds(1), null, null);
        CountingHandler handler = new CountingHandler(0);
        OutboxProperties properties = newProperties(60);
        OutboxPollingService pollerA = newPoller(properties, handler);
        OutboxPollingService pollerB = newPoller(properties, handler);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<OutboxPollResult> futureA = executor.submit(() -> {
                ready.countDown();
                start.await();
                return pollerA.pollDueEvents();
            });
            Future<OutboxPollResult> futureB = executor.submit(() -> {
                ready.countDown();
                start.await();
                return pollerB.pollDueEvents();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            OutboxPollResult resultA = futureA.get(30, TimeUnit.SECONDS);
            OutboxPollResult resultB = futureB.get(30, TimeUnit.SECONDS);

            assertThat(handler.invoked.get()).as("handler 必须只执行一次").isEqualTo(1);
            assertThat(resultA.succeeded() + resultB.succeeded()).isEqualTo(1);
            assertThat(resultA.claimed() + resultB.claimed()).isEqualTo(1);
            assertThat(resultA.skipped() + resultB.skipped()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM tb_outbox_event WHERE business_key = ?",
                    String.class, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1"))
                    .isEqualTo("SUCCESS");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void leaseExpiredProcessingIsReclaimedAndHandled() {
        long orderId = insertOutbox("PROCESSING", 1, LocalDateTime.now().plusSeconds(30),
                "token-old", LocalDateTime.now().minusSeconds(5));
        CountingHandler handler = new CountingHandler(0);
        OutboxPollingService poller = newPoller(newProperties(60), handler);

        OutboxPollResult result = poller.pollDueEvents();

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(handler.invoked.get()).isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM tb_outbox_event WHERE business_key = ?",
                String.class, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1");
        Integer retryCount = jdbcTemplate.queryForObject(
                "SELECT retry_count FROM tb_outbox_event WHERE business_key = ?",
                Integer.class, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1");
        assertThat(status).isEqualTo("SUCCESS");
        assertThat(retryCount).isEqualTo(2);
    }

    @Test
    void leaseExpiredAtMaxRetryGoesDeadWithoutHandler() {
        long orderId = insertOutbox("PROCESSING", 4, LocalDateTime.now().plusSeconds(30),
                "token-old", LocalDateTime.now().minusSeconds(5));
        CountingHandler handler = new CountingHandler(0);
        OutboxPollingService poller = newPoller(newProperties(60), handler);

        OutboxPollResult result = poller.pollDueEvents();

        assertThat(handler.invoked.get()).as("达上限时不得进入 handler").isZero();
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM tb_outbox_event WHERE business_key = ?",
                String.class, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1");
        Integer retryCount = jdbcTemplate.queryForObject(
                "SELECT retry_count FROM tb_outbox_event WHERE business_key = ?",
                Integer.class, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1");
        String lastError = jdbcTemplate.queryForObject(
                "SELECT last_error_code FROM tb_outbox_event WHERE business_key = ?",
                String.class, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1");
        assertThat(status).isEqualTo("DEAD");
        assertThat(retryCount).isEqualTo(5);
        assertThat(lastError).isEqualTo("LEASE_EXPIRED");
        assertThat(result.dead()).isEqualTo(1);
    }

    @Test
    void oldTokenCannotOverwriteAfterNewInstanceCompletes() {
        long orderId = insertOutbox("PROCESSING", 0, LocalDateTime.now().plusSeconds(30),
                "token-old", LocalDateTime.now().minusSeconds(5));
        CountingHandler handler = new CountingHandler(0);
        OutboxPollingService poller = newPoller(newProperties(60), handler);

        poller.pollDueEvents();

        String finalStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tb_outbox_event WHERE business_key = ?",
                String.class, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1");
        assertThat(finalStatus).isEqualTo("SUCCESS");

        // 旧实例使用已失效 token 尝试最终更新，必须 affected=0 且不能覆盖新状态
        Long rowId = rowIdByBusinessKey(orderId);
        int affected = outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, rowId)
                .eq(OutboxEvent::getStatus, OutboxEventStatus.PROCESSING.name())
                .eq(OutboxEvent::getLockToken, "token-old")
                .set(OutboxEvent::getStatus, OutboxEventStatus.DEAD.name())
                .set(OutboxEvent::getLastErrorCode, "OLD_TOKEN_ATTEMPT"));
        assertThat(affected).isZero();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tb_outbox_event WHERE id = ?", String.class, rowId))
                .isEqualTo("SUCCESS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lock_token FROM tb_outbox_event WHERE id = ?", String.class, rowId))
                .isNull();
    }

    @Test
    void leaseLostIsCountedWhenOldInstanceFinishesAfterReclaim() throws Exception {
        long orderId = insertOutbox("PENDING", 0, LocalDateTime.now().minusSeconds(1), null, null);
        CountingHandler slowHandler = new CountingHandler(4_000L);
        CountingHandler fastHandler = new CountingHandler(0);
        OutboxProperties properties = newProperties(2);
        OutboxPollingService pollerA = newPoller(properties, slowHandler);
        OutboxPollingService pollerB = newPoller(properties, fastHandler);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch startA = new CountDownLatch(1);
            Future<OutboxPollResult> futureA = executor.submit(() -> {
                startA.await();
                return pollerA.pollDueEvents();
            });
            startA.countDown();

            // 等待实例 A 完成领取（PROCESSING + 新 token）
            long deadline = System.currentTimeMillis() + 5_000;
            String token = null;
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(200);
                token = jdbcTemplate.queryForObject(
                        "SELECT lock_token FROM tb_outbox_event WHERE business_key = ?",
                        String.class, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1");
                if (token != null) {
                    break;
                }
            }
            assertThat(token).as("实例 A 必须已领取并持有新 token").isNotNull().isNotEqualTo("token-old");

            // 等待租约过期（lease=2s），实例 B 回收并完成
            Thread.sleep(2_500L);
            OutboxPollResult resultB = pollerB.pollDueEvents();
            assertThat(resultB.succeeded()).isEqualTo(1);

            // 实例 A 的 handler 4s 后返回，最终 token 守卫更新必然 affected=0 → LEASE_LOST
            OutboxPollResult resultA = futureA.get(30, TimeUnit.SECONDS);
            assertThat(resultA.claimed()).isEqualTo(1);
            assertThat(resultA.leaseLost()).isEqualTo(1);
            assertThat(resultA.succeeded()).isZero();

            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM tb_outbox_event WHERE business_key = ?",
                    String.class, "VOUCHER_ORDER:CLOSED:" + orderId + ":V1"))
                    .isEqualTo("SUCCESS");
        } finally {
            executor.shutdownNow();
        }
    }
}
