package com.linklife.trade.lifecycle.integration;

import com.linklife.integration.support.ManualIntegrationEnvironment;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 017G 手工集成测试公共支持：专用隔离 MySQL/Redis 环境属性映射与数据准备/清理工具。
 *
 * <p>连接参数只来自 {@link ManualIntegrationEnvironment} 允许的环境变量
 * （host/port/database/URL/username/password），不打印、不写入正式代码。
 * 数据库 schema 必须为含 test/stage1 的测试库（本任务使用 linklife_it_017g_stage1_test），
 * 由 {@link ManualIntegrationEnvironment.FullIsolationRequired} 在 Spring 上下文创建前校验。</p>
 */
abstract class Stage3E017gIntegrationSupport {

    protected static final AtomicLong NEXT_ORDER_ID = new AtomicLong(9_000_000_000L);
    protected static final AtomicLong NEXT_VOUCHER_ID = new AtomicLong(8_000_000_000L);

    @Resource
    protected JdbcTemplate jdbcTemplate;

    @Resource
    protected StringRedisTemplate stringRedisTemplate;

    @DynamicPropertySource
    static void manualEnvironmentProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", ManualIntegrationEnvironment::redisHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(ManualIntegrationEnvironment.redisPort()));
        registry.add("spring.data.redis.database", () -> String.valueOf(ManualIntegrationEnvironment.redisDatabase()));
        String redisPassword = ManualIntegrationEnvironment.redisPassword();
        if (redisPassword != null) {
            registry.add("spring.data.redis.password", () -> redisPassword);
        }
        registry.add("spring.datasource.url", ManualIntegrationEnvironment::dbUrl);
        registry.add("spring.datasource.username", ManualIntegrationEnvironment::dbUsername);
        String dbPassword = ManualIntegrationEnvironment.dbPassword();
        if (dbPassword != null) {
            registry.add("spring.datasource.password", () -> dbPassword);
        }
    }

    protected long nextOrderId() {
        return NEXT_ORDER_ID.getAndIncrement();
    }

    protected long nextVoucherId() {
        return NEXT_VOUCHER_ID.getAndIncrement();
    }

    protected void insertSeckillVoucher(long voucherId, int stock) {
        jdbcTemplate.update("INSERT INTO tb_seckill_voucher (voucher_id, stock, begin_time, end_time) "
                + "VALUES (?, ?, '2026-01-01 00:00:00', '2027-12-31 23:59:59')", voucherId, stock);
    }

    protected void insertOrder(long orderId, long userId, long voucherId, int status, LocalDateTime createTime) {
        jdbcTemplate.update("INSERT INTO tb_voucher_order (id, user_id, voucher_id, status, create_time, update_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                orderId, userId, voucherId, status, createTime, LocalDateTime.now());
    }

    protected int orderStatus(long orderId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tb_voucher_order WHERE id = ?", Integer.class, orderId);
        return status == null ? -1 : status;
    }

    protected int seckillStock(long voucherId) {
        Integer stock = jdbcTemplate.queryForObject(
                "SELECT stock FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId);
        return stock == null ? -1 : stock;
    }

    protected int statusLogCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_order_status_log WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    protected int outboxCount(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event WHERE aggregate_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    protected String outboxStatus(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tb_outbox_event WHERE aggregate_id = ?", String.class, orderId);
    }

    protected String outboxBusinessKey(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT business_key FROM tb_outbox_event WHERE aggregate_id = ?", String.class, orderId);
    }

    protected boolean outboxLeaseFieldsEmpty(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event WHERE aggregate_id = ? "
                        + "AND lock_token IS NULL AND locked_until IS NULL AND processing_started_time IS NULL",
                Integer.class, orderId);
        return count != null && count == 1;
    }

    protected void cleanupOrder(long orderId, long voucherId) {
        jdbcTemplate.update("DELETE FROM tb_outbox_event WHERE aggregate_id = ?", orderId);
        jdbcTemplate.update("DELETE FROM tb_order_status_log WHERE order_id = ?", orderId);
        jdbcTemplate.update("DELETE FROM tb_voucher_order WHERE id = ?", orderId);
        jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
    }

    protected String stockKey(long voucherId) {
        return "transaction:seckill:stock:" + voucherId;
    }

    protected String markerKey(long orderId) {
        return "transaction:order:close:comp:" + orderId;
    }

    protected String orderSetKey(long voucherId) {
        return "transaction:seckill:order:" + voucherId;
    }
}
