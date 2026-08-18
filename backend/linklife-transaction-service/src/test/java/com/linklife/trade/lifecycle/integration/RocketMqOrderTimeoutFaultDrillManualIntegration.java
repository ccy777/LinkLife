package com.linklife.trade.lifecycle.integration;

import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.trade.application.OutboxPollingService;
import com.linklife.trade.application.VoucherOrderTransactionalService;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.lifecycle.timeout.OrderPaymentTimeoutEvent;
import com.linklife.trade.lifecycle.timeout.OrderTimeoutRocketMqProperties;
import com.linklife.trade.lifecycle.timeout.RocketMqOrderTimeoutClientManager;
import com.linklife.trade.lifecycle.timeout.RocketMqOrderTimeoutMessageListener;
import com.linklife.transaction.TransactionServiceApplication;
import jakarta.annotation.Resource;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地单节点真实故障演练。Broker 停启由外部编排器通过本地 marker 目录协调，
 * 测试本身只访问隔离的 MySQL、Redis 与 RocketMQ 资源。
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
        "linklife.trade.order-timeout.payment-timeout=60s",
        "linklife.trade.order-timeout.initial-delay-ms=3600000",
        "linklife.trade.order-timeout.scan-delay-ms=3600000",
        "linklife.trade.order-timeout.rocketmq.enabled=true",
        "linklife.trade.order-timeout.rocketmq.endpoints=${LINKLIFE_MANUAL_ROCKETMQ_ENDPOINTS:127.0.0.1:38081}",
        "linklife.trade.order-timeout.rocketmq.topic=linklife-order-payment-timeout-it",
        "linklife.trade.order-timeout.rocketmq.tag=PAYMENT_TIMEOUT_CHECK",
        "linklife.trade.order-timeout.rocketmq.consumer-group=linklife-rmq-it-timeout-v1",
        "linklife.trade.order-timeout.rocketmq.ssl-enabled=false",
        "linklife.trade.order-timeout.rocketmq.request-timeout=3s"
})
class RocketMqOrderTimeoutFaultDrillManualIntegration extends Stage3E017gIntegrationSupport {

    private static final Path MARKERS = Path.of("..", "..", ".linklife-local", "runtime",
            "rocketmq-delay-timeout").toAbsolutePath().normalize();

    @Resource
    private VoucherOrderTransactionalService createService;

    @Resource
    private OutboxPollingService outboxPollingService;

    @Resource
    private RocketMqOrderTimeoutClientManager rocketMqTimeoutClientManager;

    @Resource
    private ClientServiceProvider provider;

    @Resource
    private OrderTimeoutRocketMqProperties properties;

    @Resource
    private RocketMqOrderTimeoutMessageListener listener;

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
    void brokerDownDuringPublishKeepsIntentRetryableAndRecovers() throws Exception {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = orderId + 100;
        resetMarkers("broker-down-ready", "broker-down-confirmed",
                "publish-failure-observed", "broker-up-confirmed");
        try {
            prepareCreation(orderId, userId, voucherId);
            createService.process(order(orderId, userId, voucherId));

            signal("broker-down-ready");
            awaitMarker("broker-down-confirmed", 120);
            outboxPollingService.pollDueEvents();

            assertThat(timeoutEventField(orderId, "status")).isEqualTo("PENDING");
            assertThat(Integer.parseInt(timeoutEventField(orderId, "retry_count"))).isGreaterThanOrEqualTo(1);
            assertThat(timeoutEventField(orderId, "last_error_code"))
                    .isIn("ROCKETMQ_SEND_FAILED", "HANDLER_EXCEPTION");
            signal("publish-failure-observed");

            awaitMarker("broker-up-confirmed", 120);
            awaitTimeoutEventSuccess(orderId, 90);
            awaitOrderStatus(orderId, VoucherOrderStatus.CANCELED.getCode(), 90);
            drainClosedOutbox(orderId, 30);
            assertSingleCloseAndCompensation(orderId, voucherId);
        } finally {
            cleanup(orderId, voucherId);
            resetMarkers("broker-down-ready", "broker-down-confirmed",
                    "publish-failure-observed", "broker-up-confirmed");
        }
    }

    @Test
    void timerMessageSurvivesNameServerAndBrokerRestart() throws Exception {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = orderId + 100;
        resetMarkers("restart-ready");
        try {
            prepareCreation(orderId, userId, voucherId);
            createService.process(order(orderId, userId, voucherId));
            outboxPollingService.pollDueEvents();
            assertThat(timeoutEventField(orderId, "status")).isEqualTo("SUCCESS");

            signal("restart-ready");
            awaitOrderStatus(orderId, VoucherOrderStatus.CANCELED.getCode(), 120);
            drainClosedOutbox(orderId, 30);
            assertSingleCloseAndCompensation(orderId, voucherId);
        } finally {
            cleanup(orderId, voucherId);
            resetMarkers("restart-ready");
        }
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void consumerDownAtDueConsumesAfterRecovery() throws Exception {
        long orderId = nextOrderId();
        long voucherId = nextVoucherId();
        long userId = orderId + 100;
        PushConsumer replacement = null;
        try {
            prepareCreation(orderId, userId, voucherId);
            createService.process(order(orderId, userId, voucherId));
            outboxPollingService.pollDueEvents();
            rocketMqTimeoutClientManager.stop();

            Thread.sleep(63_000L);
            assertThat(orderStatus(orderId)).isEqualTo(VoucherOrderStatus.UNPAID.getCode());

            replacement = newConsumer();
            awaitOrderStatus(orderId, VoucherOrderStatus.CANCELED.getCode(), 90);
            drainClosedOutbox(orderId, 30);
            assertSingleCloseAndCompensation(orderId, voucherId);
        } finally {
            if (replacement != null) replacement.close();
            cleanup(orderId, voucherId);
        }
    }

    private PushConsumer newConsumer() throws Exception {
        ClientConfiguration configuration = ClientConfiguration.newBuilder()
                .setEndpoints(properties.getEndpoints())
                .enableSsl(properties.isSslEnabled())
                .setRequestTimeout(properties.getRequestTimeout())
                .build();
        FilterExpression filter = new FilterExpression(properties.getTag(), FilterExpressionType.TAG);
        return provider.newPushConsumerBuilder()
                .setClientConfiguration(configuration)
                .setConsumerGroup(properties.getConsumerGroup())
                .setSubscriptionExpressions(Map.of(properties.getTopic(), filter))
                .setConsumptionThreadCount(properties.getConsumptionThreadCount())
                .setMessageListener(listener)
                .build();
    }

    private void prepareCreation(long orderId, long userId, long voucherId) {
        insertSeckillVoucher(voucherId, 5);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "7");
        stringRedisTemplate.opsForSet().add(orderSetKey(voucherId), String.valueOf(userId));
    }

    private VoucherOrder order(long orderId, long userId, long voucherId) {
        return new VoucherOrder().setId(orderId).setUserId(userId).setVoucherId(voucherId);
    }

    private String timeoutEventField(long orderId, String field) {
        if (!field.matches("status|retry_count|last_error_code")) {
            throw new IllegalArgumentException("unsupported field");
        }
        return jdbcTemplate.queryForObject("SELECT " + field
                        + " FROM tb_outbox_event WHERE aggregate_id=? AND event_type=?",
                String.class, orderId, OrderPaymentTimeoutEvent.EVENT_TYPE);
    }

    private void awaitTimeoutEventSuccess(long orderId, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            outboxPollingService.pollDueEvents();
            if ("SUCCESS".equals(timeoutEventField(orderId, "status"))) return;
            Thread.sleep(500L);
        }
        assertThat(timeoutEventField(orderId, "status")).isEqualTo("SUCCESS");
    }

    private void awaitOrderStatus(long orderId, int expected, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (orderStatus(orderId) == expected) return;
            Thread.sleep(500L);
        }
        assertThat(orderStatus(orderId)).isEqualTo(expected);
    }

    private void drainClosedOutbox(long orderId, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            outboxPollingService.pollDueEvents();
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM tb_outbox_event WHERE aggregate_id=? "
                            + "AND event_type='ORDER_CLOSED' AND status='SUCCESS'", Integer.class, orderId);
            if (count != null && count == 1) return;
            Thread.sleep(300L);
        }
        throw new AssertionError("ORDER_CLOSED outbox did not converge");
    }

    private void assertSingleCloseAndCompensation(long orderId, long voucherId) {
        assertThat(seckillStock(voucherId)).isEqualTo(5);
        assertThat(statusLogCount(orderId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event WHERE aggregate_id=? AND event_type='ORDER_CLOSED'",
                Integer.class, orderId)).isEqualTo(1);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("8");
        assertThat(stringRedisTemplate.hasKey(markerKey(orderId))).isTrue();
    }

    private void cleanup(long orderId, long voucherId) {
        stringRedisTemplate.delete(stockKey(voucherId));
        stringRedisTemplate.delete(markerKey(orderId));
        stringRedisTemplate.delete(orderSetKey(voucherId));
        cleanupOrder(orderId, voucherId);
    }

    private static void signal(String marker) throws IOException {
        Files.createDirectories(MARKERS);
        Files.writeString(MARKERS.resolve(marker), "ready\n");
    }

    private static void awaitMarker(String marker, int timeoutSeconds) throws Exception {
        Path path = MARKERS.resolve(marker);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)) return;
            Thread.sleep(250L);
        }
        throw new AssertionError("fault drill marker timeout: " + marker);
    }

    private static void resetMarkers(String... markers) throws IOException {
        for (String marker : markers) Files.deleteIfExists(MARKERS.resolve(marker));
    }
}
