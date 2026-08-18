package com.linklife.trade.lifecycle.integration;

import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.trade.application.OrderTimeoutCloseService;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.lifecycle.timeout.RocketMqOrderTimeoutClientManager;
import com.linklife.transaction.TransactionServiceApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Broker 已不可达时的真实冷启动与 Scheduler fallback 表征。 */
@ManualIntegrationEnvironment.FullIsolationRequired
class RocketMqBrokerDownColdStartManualIntegration {

    private static final Path MARKERS = Path.of("..", "..", ".codex-work", "runtime",
            "rocketmq-delay-timeout").toAbsolutePath().normalize();

    @Test
    void transactionStartsAndSchedulerCanCloseWhileBrokerIsAlreadyDown() throws Exception {
        resetMarkers("cold-start-ready", "cold-start-broker-down",
                "cold-start-scheduler-pass", "cold-start-broker-up", "cold-start-complete");
        signal("cold-start-ready");
        awaitMarker("cold-start-broker-down", 120);

        ConfigurableApplicationContext context = null;
        long orderId = 9_700_000_001L;
        long voucherId = 8_700_000_001L;
        try {
            context = new SpringApplicationBuilder(TransactionServiceApplication.class)
                    .web(WebApplicationType.NONE)
                    .run(applicationArguments());

            assertThat(context.isActive()).isTrue();
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            jdbc.update("INSERT INTO tb_seckill_voucher "
                            + "(voucher_id, stock, begin_time, end_time) VALUES (?, 4, ?, ?)",
                    voucherId, LocalDateTime.of(2026, 1, 1, 0, 0),
                    LocalDateTime.of(2027, 12, 31, 23, 59, 59));
            jdbc.update("INSERT INTO tb_voucher_order "
                            + "(id, user_id, voucher_id, status, create_time, payment_due_at, update_time) "
                            + "VALUES (?, ?, ?, 1, ?, ?, ?)",
                    orderId, orderId + 100, voucherId, LocalDateTime.now().minusMinutes(1),
                    Instant.now().minusSeconds(5), LocalDateTime.now());

            context.getBean(OrderTimeoutCloseService.class).closeExpiredOrders();

            assertThat(jdbc.queryForObject(
                    "SELECT status FROM tb_voucher_order WHERE id=?", Integer.class, orderId))
                    .isEqualTo(VoucherOrderStatus.CANCELED.getCode());
            assertThat(jdbc.queryForObject(
                    "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=?", Integer.class, voucherId))
                    .isEqualTo(5);

            signal("cold-start-scheduler-pass");
            awaitMarker("cold-start-broker-up", 120);
            awaitRocketMqClients(context.getBean(RocketMqOrderTimeoutClientManager.class), 120);
        } finally {
            if (context != null) {
                JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                jdbc.update("DELETE FROM tb_outbox_event WHERE aggregate_id=?", orderId);
                jdbc.update("DELETE FROM tb_order_status_log WHERE order_id=?", orderId);
                jdbc.update("DELETE FROM tb_voucher_order WHERE id=?", orderId);
                jdbc.update("DELETE FROM tb_seckill_voucher WHERE voucher_id=?", voucherId);
                context.close();
            }
            signal("cold-start-complete");
        }
    }

    private Map<String, Object> applicationProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.cloud.nacos.discovery.register-enabled", "false");
        properties.put("linklife.runtime.production-validation-enabled", "false");
        properties.put("spring.datasource.url", ManualIntegrationEnvironment.dbUrl());
        properties.put("spring.datasource.username", ManualIntegrationEnvironment.dbUsername());
        String dbPassword = ManualIntegrationEnvironment.dbPassword();
        if (dbPassword != null) properties.put("spring.datasource.password", dbPassword);
        properties.put("spring.data.redis.host", ManualIntegrationEnvironment.redisHost());
        properties.put("spring.data.redis.port", ManualIntegrationEnvironment.redisPort());
        properties.put("spring.data.redis.database", ManualIntegrationEnvironment.redisDatabase());
        String redisPassword = ManualIntegrationEnvironment.redisPassword();
        if (redisPassword != null) properties.put("spring.data.redis.password", redisPassword);
        properties.put("linklife.trade.outbox.enabled", "true");
        properties.put("linklife.trade.outbox.initial-delay-ms", "3600000");
        properties.put("linklife.trade.order-timeout.enabled", "true");
        properties.put("linklife.trade.order-timeout.initial-delay-ms", "3600000");
        properties.put("linklife.trade.order-timeout.rocketmq.enabled", "true");
        properties.put("linklife.trade.order-timeout.rocketmq.endpoints",
                System.getenv().getOrDefault("LINKLIFE_MANUAL_ROCKETMQ_ENDPOINTS", "broker:8081"));
        properties.put("linklife.trade.order-timeout.rocketmq.topic",
                "linklife-order-payment-timeout-it");
        properties.put("linklife.trade.order-timeout.rocketmq.tag", "PAYMENT_TIMEOUT_CHECK");
        properties.put("linklife.trade.order-timeout.rocketmq.consumer-group",
                "linklife-codex-it-timeout-v1");
        properties.put("linklife.trade.order-timeout.rocketmq.ssl-enabled", "false");
        properties.put("linklife.trade.order-timeout.rocketmq.request-timeout", "3s");
        return properties;
    }

    private String[] applicationArguments() {
        return applicationProperties().entrySet().stream()
                .map(entry -> "--" + entry.getKey() + "=" + entry.getValue())
                .toArray(String[]::new);
    }

    private static void signal(String marker) throws IOException {
        Files.createDirectories(MARKERS);
        Files.writeString(MARKERS.resolve(marker), "ready\n");
    }

    private static void awaitMarker(String marker, int timeoutSeconds) throws Exception {
        Path path = MARKERS.resolve(marker);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(path)) return;
            Thread.sleep(250L);
        }
        throw new AssertionError("fault drill marker timeout: " + marker);
    }

    private static void awaitRocketMqClients(
            RocketMqOrderTimeoutClientManager clientManager, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            if (clientManager.isProducerReady() && clientManager.isConsumerReady()) return;
            Thread.sleep(250L);
        }
        throw new AssertionError("RocketMQ clients did not recover after Broker restart");
    }

    private static void resetMarkers(String... markers) throws IOException {
        for (String marker : markers) Files.deleteIfExists(MARKERS.resolve(marker));
    }
}
