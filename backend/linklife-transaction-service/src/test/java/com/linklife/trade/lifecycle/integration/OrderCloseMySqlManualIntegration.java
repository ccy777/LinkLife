package com.linklife.trade.lifecycle.integration;

import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.trade.application.OrderCloseTransactionService;
import com.linklife.trade.lifecycle.close.OrderCloseCommand;
import com.linklife.trade.lifecycle.close.OrderCloseReasonCode;
import com.linklife.trade.lifecycle.close.OrderCloseResult;
import com.linklife.trade.lifecycle.close.OrderCloseTriggerType;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 017G 手工集成：MySQL Schema、关闭事务成功提交/整体回滚/幂等、并发关闭与 REPEATABLE READ 当前读。
 *
 * <p>默认不进 Surefire；显式运行需设置
 * {@code LINKLIFE_MANUAL_INTEGRATION_ENABLED=true}、{@code LINKLIFE_MANUAL_CONFIRM_ISOLATED=true}
 * 及专用隔离 MySQL/Redis 连接变量，且 schema 必须为 linklife_it_017g_*（含 test/stage1）。</p>
 */
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_INTEGRATION_ENABLED", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_CONFIRM_ISOLATED", matches = "(?i)true")
@ManualIntegrationEnvironment.FullIsolationRequired
  @SpringBootTest(classes = com.linklife.transaction.TransactionServiceApplication.class)
class OrderCloseMySqlManualIntegration extends Stage3E017gIntegrationSupport {

    @Resource
    private OrderCloseTransactionService orderCloseTransactionService;

    @Resource
    private DataSource dataSource;

    private final List<long[]> createdRows = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (long[] pair : createdRows) {
            cleanupOrder(pair[0], pair[1]);
        }
        createdRows.clear();
    }

    private long newOrder(long userId, long voucherId, int status, LocalDateTime createTime) {
        long orderId = nextOrderId();
        insertOrder(orderId, userId, voucherId, status, createTime);
        return orderId;
    }

    private OrderCloseCommand userCancel(long orderId, long userId, LocalDateTime now) {
        return new OrderCloseCommand(orderId, userId, OrderCloseTriggerType.USER_CANCEL, null,
                OrderCloseReasonCode.USER_CANCEL, now);
    }

    private OrderCloseCommand timeoutClose(long orderId, Instant dueAtCutoff, LocalDateTime now) {
        return new OrderCloseCommand(orderId, null, OrderCloseTriggerType.TIMEOUT_CLOSE, dueAtCutoff,
                OrderCloseReasonCode.TIMEOUT_EXPIRED, now);
    }

    @Test
    void schemaContractCreatedByOfficialDdl() {
        List<String> engines = jdbcTemplate.queryForList(
                "SELECT CONCAT(TABLE_NAME, ':', ENGINE) FROM information_schema.TABLES "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME IN ('tb_order_status_log','tb_outbox_event')",
                String.class);
        assertThat(engines).containsExactlyInAnyOrder("tb_order_status_log:InnoDB", "tb_outbox_event:InnoDB");

        List<String> outboxUniques = jdbcTemplate.queryForList(
                "SELECT CONSTRAINT_NAME FROM information_schema.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_outbox_event' AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class);
        assertThat(outboxUniques).contains("uk_outbox_event_id", "uk_outbox_business_key");

        List<String> outboxIndexes = jdbcTemplate.queryForList(
                "SELECT INDEX_NAME FROM information_schema.STATISTICS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'tb_outbox_event' "
                        + "AND INDEX_NAME LIKE 'idx_outbox_%' GROUP BY INDEX_NAME",
                String.class);
        assertThat(outboxIndexes).contains("idx_outbox_status_next_retry", "idx_outbox_status_locked_until");

        Integer leaseNullable = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'tb_outbox_event' AND COLUMN_NAME IN ('lock_token','locked_until',"
                        + "'processing_started_time','completed_time') AND IS_NULLABLE = 'YES'",
                Integer.class);
        assertThat(leaseNullable).isEqualTo(4);

        Integer precision = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'tb_outbox_event' AND DATA_TYPE = 'datetime' "
                        + "AND DATETIME_PRECISION = 0 AND COLUMN_NAME IN ('locked_until','processing_started_time',"
                        + "'completed_time','next_retry_time','updated_time')",
                Integer.class);
        assertThat(precision).isEqualTo(5);
    }

    @Test
    void successfulCloseCommitsEverything() {
        long voucherId = nextVoucherId();
        long userId = 1001L;
        insertSeckillVoucher(voucherId, 10);
        long orderId = newOrder(userId, voucherId, 1, LocalDateTime.now().minusMinutes(10));
        createdRows.add(new long[]{orderId, voucherId});

        OrderCloseResult result = orderCloseTransactionService.close(
                userCancel(orderId, userId, LocalDateTime.now()));

        assertThat(result).isEqualTo(OrderCloseResult.CLOSED);
        assertThat(orderStatus(orderId)).isEqualTo(4);
        assertThat(seckillStock(voucherId)).isEqualTo(11);
        assertThat(statusLogCount(orderId)).isEqualTo(1);
        assertThat(outboxCount(orderId)).isEqualTo(1);
        assertThat(outboxStatus(orderId)).isEqualTo("PENDING");
        assertThat(outboxBusinessKey(orderId)).isEqualTo("VOUCHER_ORDER:CLOSED:" + orderId + ":V1");
        assertThat(outboxLeaseFieldsEmpty(orderId)).isTrue();
    }

    @Test
    void statusLogUniqueConflictRollsBackWholeTransaction() {
        long voucherId = nextVoucherId();
        long userId = 1002L;
        insertSeckillVoucher(voucherId, 7);
        long orderId = newOrder(userId, voucherId, 1, LocalDateTime.now().minusMinutes(10));
        createdRows.add(new long[]{orderId, voucherId});

        // 预置同一条幂等键的状态日志，使事务内 insert 触发 uk_order_status_log_idem 冲突
        jdbcTemplate.update("INSERT INTO tb_order_status_log "
                        + "(order_id, from_status, to_status, trigger_type, operator_type, operator_id, "
                        + "reason_code, reason_detail, idempotency_key, created_time) "
                        + "VALUES (?, 1, 4, 'USER_CANCEL', 'USER', ?, 'USER_CANCEL', 'pre-seeded', ?, NOW())",
                orderId, userId, "ORDER_STATUS:" + orderId + ":1:4");

        assertThatThrownBy(() -> orderCloseTransactionService.close(
                userCancel(orderId, userId, LocalDateTime.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(orderStatus(orderId)).as("订单必须整体回滚为 UNPAID").isEqualTo(1);
        assertThat(seckillStock(voucherId)).as("库存必须整体回滚").isEqualTo(7);
        assertThat(statusLogCount(orderId)).isEqualTo(1);
        assertThat(outboxCount(orderId)).isZero();
    }

    @Test
    void outboxBusinessKeyConflictRollsBackWholeTransaction() {
        long voucherId = nextVoucherId();
        long userId = 1003L;
        insertSeckillVoucher(voucherId, 5);
        long orderId = newOrder(userId, voucherId, 1, LocalDateTime.now().minusMinutes(10));
        createdRows.add(new long[]{orderId, voucherId});

        // 预置同一条 business_key 的 Outbox，使事务内 insert 触发 uk_outbox_business_key 冲突
        jdbcTemplate.update("INSERT INTO tb_outbox_event "
                        + "(event_id, business_key, aggregate_type, aggregate_id, event_type, event_version, "
                        + "payload, status, retry_count, next_retry_time, created_time, updated_time) "
                        + "VALUES (?, ?, 'VOUCHER_ORDER', ?, 'ORDER_CLOSED', 1, '{}', 'PENDING', 0, NOW(), NOW(), NOW())",
                "it-pre-" + UUID.randomUUID(), "VOUCHER_ORDER:CLOSED:" + orderId + ":V1", orderId);

        assertThatThrownBy(() -> orderCloseTransactionService.close(
                userCancel(orderId, userId, LocalDateTime.now())))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        assertThat(orderStatus(orderId)).as("订单必须整体回滚为 UNPAID").isEqualTo(1);
        assertThat(seckillStock(voucherId)).as("库存必须整体回滚").isEqualTo(5);
        assertThat(statusLogCount(orderId)).isZero();
        assertThat(outboxCount(orderId)).isEqualTo(1);
    }

    @Test
    void alreadyCanceledIsIdempotentWithoutRestock() {
        long voucherId = nextVoucherId();
        long userId = 1004L;
        insertSeckillVoucher(voucherId, 9);
        long orderId = newOrder(userId, voucherId, 4, LocalDateTime.now().minusMinutes(10));
        createdRows.add(new long[]{orderId, voucherId});

        OrderCloseResult result = orderCloseTransactionService.close(
                userCancel(orderId, userId, LocalDateTime.now()));

        assertThat(result).isEqualTo(OrderCloseResult.ALREADY_CANCELED);
        assertThat(seckillStock(voucherId)).isEqualTo(9);
        assertThat(statusLogCount(orderId)).isZero();
        assertThat(outboxCount(orderId)).isZero();
    }

    @Test
    void paidAndUsedAreNotClosable() {
        long voucherId = nextVoucherId();
        long userId = 1005L;
        insertSeckillVoucher(voucherId, 3);
        long paidOrder = newOrder(userId, voucherId, 2, LocalDateTime.now().minusMinutes(10));
        createdRows.add(new long[]{paidOrder, voucherId});

        assertThat(orderCloseTransactionService.close(userCancel(paidOrder, userId, LocalDateTime.now())))
                .isEqualTo(OrderCloseResult.NOT_CLOSABLE);
        assertThat(seckillStock(voucherId)).isEqualTo(3);

        // uk_user_voucher 为全生命周期唯一：USED 订单必须使用另一张券
        long voucherId2 = nextVoucherId();
        insertSeckillVoucher(voucherId2, 3);
        long usedOrder = newOrder(userId, voucherId2, 3, LocalDateTime.now().minusMinutes(10));
        createdRows.add(new long[]{usedOrder, voucherId2});
        assertThat(orderCloseTransactionService.close(userCancel(usedOrder, userId, LocalDateTime.now())))
                .isEqualTo(OrderCloseResult.NOT_CLOSABLE);
        assertThat(seckillStock(voucherId2)).isEqualTo(3);
    }

    @Test
    void invisibleOrderForOtherUserIsNotFound() {
        long voucherId = nextVoucherId();
        long userId = 1006L;
        insertSeckillVoucher(voucherId, 4);
        long orderId = newOrder(userId, voucherId, 1, LocalDateTime.now().minusMinutes(10));
        createdRows.add(new long[]{orderId, voucherId});

        assertThat(orderCloseTransactionService.close(
                userCancel(orderId, 999999L, LocalDateTime.now())))
                .isEqualTo(OrderCloseResult.NOT_FOUND);
        assertThat(orderStatus(orderId)).isEqualTo(1);
        assertThat(seckillStock(voucherId)).isEqualTo(4);
    }

    @Test
    void timeoutBeforeCutoffIsNotClosable() {
        long voucherId = nextVoucherId();
        long userId = 1007L;
        insertSeckillVoucher(voucherId, 6);
        // create_time 晚于 cutoff，CAS 应 affected=0，回查后 NOT_CLOSABLE
        long orderId = newOrder(userId, voucherId, 1, LocalDateTime.now().minusMinutes(1));
        createdRows.add(new long[]{orderId, voucherId});

        assertThat(orderCloseTransactionService.close(
                timeoutClose(orderId, Instant.now().minusSeconds(300), LocalDateTime.now())))
                .isEqualTo(OrderCloseResult.NOT_CLOSABLE);
        assertThat(orderStatus(orderId)).isEqualTo(1);
        assertThat(seckillStock(voucherId)).isEqualTo(6);
    }

    @Test
    void unknownStatusFailsClosed() {
        long voucherId = nextVoucherId();
        long userId = 1008L;
        insertSeckillVoucher(voucherId, 2);
        long orderId = newOrder(userId, voucherId, 7, LocalDateTime.now().minusMinutes(10));
        createdRows.add(new long[]{orderId, voucherId});

        assertThatThrownBy(() -> orderCloseTransactionService.close(
                userCancel(orderId, userId, LocalDateTime.now())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(seckillStock(voucherId)).isEqualTo(2);
    }

    @Test
    void userCancelVersusTimeoutConcurrentClosesExactlyOnce() throws Exception {
        long voucherId = nextVoucherId();
        long userId = 1009L;
        insertSeckillVoucher(voucherId, 20);
        long orderId = newOrder(userId, voucherId, 1, LocalDateTime.now().minusMinutes(30));
        createdRows.add(new long[]{orderId, voucherId});

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            LocalDateTime now = LocalDateTime.now();
            Instant dueAtCutoff = now.atZone(ZoneId.systemDefault()).toInstant();
            Future<OrderCloseResult> userFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return orderCloseTransactionService.close(userCancel(orderId, userId, now));
            });
            Future<OrderCloseResult> timeoutFuture = executor.submit(() -> {
                ready.countDown();
                start.await();
                return orderCloseTransactionService.close(timeoutClose(orderId, dueAtCutoff, now));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            OrderCloseResult userResult = userFuture.get(30, TimeUnit.SECONDS);
            OrderCloseResult timeoutResult = timeoutFuture.get(30, TimeUnit.SECONDS);

            assertThat(List.of(userResult, timeoutResult))
                    .containsExactlyInAnyOrder(OrderCloseResult.CLOSED, OrderCloseResult.ALREADY_CANCELED);
            assertThat(orderStatus(orderId)).isEqualTo(4);
            assertThat(seckillStock(voucherId)).as("库存只能返还一次").isEqualTo(21);
            assertThat(statusLogCount(orderId)).isEqualTo(1);
            assertThat(outboxCount(orderId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void twoUserCancelsConcurrentClosesExactlyOnce() throws Exception {
        long voucherId = nextVoucherId();
        long userId = 1010L;
        insertSeckillVoucher(voucherId, 30);
        long orderId = newOrder(userId, voucherId, 1, LocalDateTime.now().minusMinutes(30));
        createdRows.add(new long[]{orderId, voucherId});

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            LocalDateTime now = LocalDateTime.now();
            Future<OrderCloseResult> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return orderCloseTransactionService.close(userCancel(orderId, userId, now));
            });
            Future<OrderCloseResult> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return orderCloseTransactionService.close(userCancel(orderId, userId, now));
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(OrderCloseResult.CLOSED, OrderCloseResult.ALREADY_CANCELED);
            assertThat(orderStatus(orderId)).isEqualTo(4);
            assertThat(seckillStock(voucherId)).isEqualTo(31);
            assertThat(statusLogCount(orderId)).isEqualTo(1);
            assertThat(outboxCount(orderId)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void repeatableReadCurrentReadSeesLatestCanceledAfterCasZero() throws Exception {
        long voucherId = nextVoucherId();
        long userId = 1011L;
        insertSeckillVoucher(voucherId, 1);
        long orderId = newOrder(userId, voucherId, 1, LocalDateTime.now().minusMinutes(30));
        createdRows.add(new long[]{orderId, voucherId});

        try (Connection connectionB = dataSource.getConnection()) {
            connectionB.setAutoCommit(false);
            try (Statement st = connectionB.createStatement();
                 ResultSet rs = st.executeQuery("SELECT @@transaction_isolation")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo("REPEATABLE-READ");
            }

            // 1. 事务 B 首次普通 SELECT 读到 UNPAID
            int before;
            try (PreparedStatement ps = connectionB.prepareStatement(
                    "SELECT status FROM tb_voucher_order WHERE id = ?")) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    before = rs.getInt(1);
                }
            }
            assertThat(before).isEqualTo(1);

            // 2. 事务 A（独立连接）关闭并提交 CANCELED
            assertThat(orderCloseTransactionService.close(
                    userCancel(orderId, userId, LocalDateTime.now())))
                    .isEqualTo(OrderCloseResult.CLOSED);

            // 3. 事务 B 条件 UPDATE affected=0（旧快照）
            int affected;
            try (PreparedStatement ps = connectionB.prepareStatement(
                    "UPDATE tb_voucher_order SET status = 4, update_time = NOW() WHERE id = ? AND status = 1")) {
                ps.setLong(1, orderId);
                affected = ps.executeUpdate();
            }
            assertThat(affected).isZero();

            // 4. 事务 B 使用正式 SELECT ... FOR UPDATE 当前读，必须读到最新 CANCELED 而非旧快照
            int after;
            try (PreparedStatement ps = connectionB.prepareStatement(
                    "SELECT status FROM tb_voucher_order WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, orderId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    after = rs.getInt(1);
                }
            }
            assertThat(after).isEqualTo(4);
            connectionB.rollback();
        }
    }
}
