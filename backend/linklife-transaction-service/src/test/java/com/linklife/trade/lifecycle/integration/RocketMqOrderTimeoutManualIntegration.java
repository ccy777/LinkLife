package com.linklife.trade.lifecycle.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.trade.application.OrderTimeoutCloseService;
import com.linklife.trade.application.OutboxPollingService;
import com.linklife.trade.application.VoucherOrderTransactionalService;
import com.linklife.trade.dto.OrderPaymentTimeoutEventPayload;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.lifecycle.timeout.OrderPaymentTimeoutEvent;
import com.linklife.trade.lifecycle.timeout.OrderPaymentTimeoutMessageProcessor;
import com.linklife.trade.lifecycle.timeout.OrderTimeoutRocketMqProperties;
import com.linklife.trade.lifecycle.timeout.RocketMqOrderTimeoutClientManager;
import com.linklife.transaction.TransactionServiceApplication;
import jakarta.annotation.Resource;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务专属真实 MySQL/Redis/RocketMQ 5.x 手工集成验证；普通 mvn test 因隔离条件默认跳过。
 */
@ManualIntegrationEnvironment.FullIsolationRequired
@SpringBootTest(classes = TransactionServiceApplication.class, properties = {
        "server.port=0",
        "spring.cloud.nacos.discovery.register-enabled=false",
        "linklife.runtime.production-validation-enabled=false",
        "linklife.trade.outbox.enabled=true",
        "linklife.trade.outbox.initial-delay-ms=3600000",
        "linklife.trade.outbox.scan-delay-ms=3600000",
        "linklife.trade.outbox.max-retries=20",
        "linklife.trade.outbox.retry-base-delay-ms=1000",
        "linklife.trade.outbox.retry-max-delay-ms=2000",
        "linklife.trade.order-timeout.enabled=true",
        "linklife.trade.order-timeout.payment-timeout=4s",
        "linklife.trade.order-timeout.initial-delay-ms=3600000",
        "linklife.trade.order-timeout.scan-delay-ms=3600000",
        "linklife.trade.order-timeout.rocketmq.enabled=true",
        "linklife.trade.order-timeout.rocketmq.endpoints=${LINKLIFE_MANUAL_ROCKETMQ_ENDPOINTS:127.0.0.1:38081}",
        "linklife.trade.order-timeout.rocketmq.topic=linklife-order-payment-timeout-it",
        "linklife.trade.order-timeout.rocketmq.tag=PAYMENT_TIMEOUT_CHECK",
        "linklife.trade.order-timeout.rocketmq.consumer-group=linklife-codex-it-timeout-v1",
        "linklife.trade.order-timeout.rocketmq.ssl-enabled=false",
        "linklife.trade.order-timeout.rocketmq.request-timeout=3s"
})
class RocketMqOrderTimeoutManualIntegration extends Stage3E017gIntegrationSupport {

    @Resource
    private VoucherOrderTransactionalService createService;

    @Resource
    private OutboxPollingService outboxPollingService;

    @Resource
    private OrderTimeoutCloseService timeoutCloseService;

    @Resource
    private OrderPaymentTimeoutMessageProcessor messageProcessor;

    @Resource
    private RocketMqOrderTimeoutClientManager rocketMqTimeoutClientManager;

    @Resource
    private ClientServiceProvider rocketMqClientServiceProvider;

    @Resource
    private OrderTimeoutRocketMqProperties rocketMqProperties;

    @Resource
    private ObjectMapper objectMapper;

    @BeforeEach
    void awaitRocketMqClients() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            if (rocketMqTimeoutClientManager.isProducerReady()
                    && rocketMqTimeoutClientManager.isConsumerReady()) return;
            Thread.sleep(250L);
        }
        assertThat(rocketMqTimeoutClientManager.isProducerReady()).isTrue();
        assertThat(rocketMqTimeoutClientManager.isConsumerReady()).isTrue();
    }

    @Test
    void normalTimeoutEndToEndClosesAndConvergesRedis() throws Exception {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = orderId + 100;
        try {
            prepareCreation(orderId, userId, voucherId, 5, 7);
            assertThat(createService.process(order(orderId, userId, voucherId)))
                    .isEqualTo(VoucherOrderTransactionalService.ProcessResult.CREATED);
            assertThat(seckillStock(voucherId)).isEqualTo(4);
            assertThat(eventCount(orderId, OrderPaymentTimeoutEvent.EVENT_TYPE)).isEqualTo(1);

            outboxPollingService.pollDueEvents();
            awaitOrderStatus(orderId, VoucherOrderStatus.CANCELED.getCode(), 15);
            drainOutboxUntilClosedSuccess(orderId, 10);

            assertThat(seckillStock(voucherId)).isEqualTo(5);
            assertThat(statusLogCount(orderId)).isEqualTo(1);
            assertThat(eventCount(orderId, "ORDER_CLOSED")).isEqualTo(1);
            assertThat(eventStatus(orderId, OrderPaymentTimeoutEvent.EVENT_TYPE)).isEqualTo("SUCCESS");
            assertThat(eventStatus(orderId, "ORDER_CLOSED")).isEqualTo("SUCCESS");
            assertThat(Integer.parseInt(stringRedisTemplate.opsForValue().get(stockKey(voucherId))))
                    .isEqualTo(8);
            assertThat(stringRedisTemplate.hasKey(markerKey(orderId))).isTrue();
            assertThat(stringRedisTemplate.opsForSet().isMember(orderSetKey(voucherId), String.valueOf(userId)))
                    .isTrue();
        } finally {
            cleanup(orderId, voucherId);
        }
    }

    @Test
    void paidOrderReceivingRealDelayMessageIsNotClosed() throws Exception {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = orderId + 100;
        try {
            prepareCreation(orderId, userId, voucherId, 5, 7);
            createService.process(order(orderId, userId, voucherId));
            jdbcTemplate.update("UPDATE tb_voucher_order SET status=2, pay_time=NOW() WHERE id=?", orderId);
            outboxPollingService.pollDueEvents();

            Thread.sleep(7_000L);
            assertThat(orderStatus(orderId)).isEqualTo(VoucherOrderStatus.PAID.getCode());
            assertThat(seckillStock(voucherId)).isEqualTo(4);
            assertThat(statusLogCount(orderId)).isZero();
            assertThat(eventCount(orderId, "ORDER_CLOSED")).isZero();
            assertThat(Integer.parseInt(stringRedisTemplate.opsForValue().get(stockKey(voucherId))))
                    .isEqualTo(7);
        } finally {
            cleanup(orderId, voucherId);
        }
    }

    @Test
    void duplicatePhysicalMessagesProduceOneBusinessClose() throws Exception {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = orderId + 100;
        try {
            prepareCreation(orderId, userId, voucherId, 5, 7);
            createService.process(order(orderId, userId, voucherId));
            outboxPollingService.pollDueEvents();
            String payloadJson = jdbcTemplate.queryForObject(
                    "SELECT payload FROM tb_outbox_event WHERE aggregate_id=? AND event_type=?",
                    String.class, orderId, OrderPaymentTimeoutEvent.EVENT_TYPE);
            OrderPaymentTimeoutEventPayload payload = objectMapper.readValue(
                    payloadJson, OrderPaymentTimeoutEventPayload.class);
            sendDuplicate(payloadJson, payload);
            sendDuplicate(payloadJson, payload);

            awaitOrderStatus(orderId, VoucherOrderStatus.CANCELED.getCode(), 15);
            drainOutboxUntilClosedSuccess(orderId, 10);
            assertThat(seckillStock(voucherId)).isEqualTo(5);
            assertThat(statusLogCount(orderId)).isEqualTo(1);
            assertThat(eventCount(orderId, "ORDER_CLOSED")).isEqualTo(1);
            assertThat(Integer.parseInt(stringRedisTemplate.opsForValue().get(stockKey(voucherId))))
                    .isEqualTo(8);
        } finally {
            cleanup(orderId, voucherId);
        }
    }

    @Test
    void mqProcessorAndSchedulerBarrierRaceHasOneCasWinner() throws Exception {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = orderId + 100;
        LocalDateTime createdAt = LocalDateTime.now().minusSeconds(8).withNano(0);
        Instant createdAtInstant = createdAt.atZone(ZoneId.of("Asia/Shanghai")).toInstant();
        Instant dueAt = createdAtInstant.plusSeconds(4);
        try {
            insertSeckillVoucher(voucherId, 4);
            insertOrder(orderId, userId, voucherId,
                    VoucherOrderStatus.UNPAID.getCode(), createdAt, dueAt);
            stringRedisTemplate.opsForValue().set(stockKey(voucherId), "7");
            stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
            OrderPaymentTimeoutEventPayload payload = new OrderPaymentTimeoutEventPayload(
                    UUID.randomUUID().toString(), 1, orderId, userId, voucherId,
                    createdAt, createdAtInstant, dueAt);

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<?> mq = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    messageProcessor.process(payload);
                });
                Future<?> scheduler = executor.submit(() -> {
                    ready.countDown();
                    await(start);
                    timeoutCloseService.closeExpiredOrders();
                });
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                mq.get(10, TimeUnit.SECONDS);
                scheduler.get(10, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }

            assertThat(orderStatus(orderId)).isEqualTo(VoucherOrderStatus.CANCELED.getCode());
            assertThat(seckillStock(voucherId)).isEqualTo(5);
            assertThat(statusLogCount(orderId)).isEqualTo(1);
            assertThat(eventCount(orderId, "ORDER_CLOSED")).isEqualTo(1);
            drainOutboxUntilClosedSuccess(orderId, 10);
            assertThat(Integer.parseInt(stringRedisTemplate.opsForValue().get(stockKey(voucherId))))
                    .isEqualTo(8);
        } finally {
            cleanup(orderId, voucherId);
        }
    }

    private void prepareCreation(long orderId, long userId, long voucherId,
                                 int mysqlStock, int redisStock) {
        insertSeckillVoucher(voucherId, mysqlStock);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), String.valueOf(redisStock));
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
    }

    private VoucherOrder order(long orderId, long userId, long voucherId) {
        return new VoucherOrder().setId(orderId).setUserId(userId).setVoucherId(voucherId);
    }

    private void sendDuplicate(String payloadJson, OrderPaymentTimeoutEventPayload payload) throws Exception {
        long timestamp = payload.dueAt().toEpochMilli();
        Message message = rocketMqClientServiceProvider.newMessageBuilder()
                .setTopic(rocketMqProperties.getTopic())
                .setTag(rocketMqProperties.getTag())
                .setKeys(payload.eventId(), OrderPaymentTimeoutEvent.businessKey(payload.orderId()))
                .setBody(payloadJson.getBytes(StandardCharsets.UTF_8))
                .setDeliveryTimestamp(timestamp)
                .build();
        assertThat(rocketMqTimeoutClientManager.send(message).getMessageId()).isNotNull();
    }

    private void awaitOrderStatus(long orderId, int expected, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (orderStatus(orderId) == expected) return;
            Thread.sleep(200L);
        }
        assertThat(orderStatus(orderId)).isEqualTo(expected);
    }

    private void drainOutboxUntilClosedSuccess(long orderId, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            outboxPollingService.pollDueEvents();
            if (eventCount(orderId, "ORDER_CLOSED") == 1
                    && "SUCCESS".equals(eventStatus(orderId, "ORDER_CLOSED"))) return;
            Thread.sleep(200L);
        }
        assertThat(eventStatus(orderId, "ORDER_CLOSED")).isEqualTo("SUCCESS");
    }

    private int eventCount(long orderId, String eventType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event WHERE aggregate_id=? AND event_type=?",
                Integer.class, orderId, eventType);
        return count == null ? 0 : count;
    }

    private String eventStatus(long orderId, String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tb_outbox_event WHERE aggregate_id=? AND event_type=?",
                String.class, orderId, eventType);
    }

    private void cleanup(long orderId, long voucherId) {
        stringRedisTemplate.delete(stockKey(voucherId));
        stringRedisTemplate.delete(markerKey(orderId));
        stringRedisTemplate.delete(orderSetKey(voucherId));
        cleanupOrder(orderId, voucherId);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("barrier timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("barrier interrupted", e);
        }
    }
}
