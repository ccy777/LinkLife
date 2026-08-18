package com.linklife.trade.lifecycle.integration;

import com.linklife.integration.support.ManualIntegrationEnvironment;
import com.linklife.promotion.entity.Voucher;
import com.linklife.promotion.service.impl.VoucherServiceImpl;
import com.linklife.trade.application.OutboxPollingService;
import com.linklife.trade.lifecycle.outbox.OutboxEventRouter;
import com.linklife.trade.lifecycle.outbox.OutboxPollResult;
import com.linklife.trade.lifecycle.outbox.OutboxProperties;
import com.linklife.trade.lifecycle.outbox.SeckillVoucherInitializeAdapter;
import com.linklife.trade.lifecycle.outbox.SeckillVoucherInitializeCommand;
import com.linklife.trade.lifecycle.outbox.SeckillVoucherInitializeResult;
import com.linklife.trade.mapper.OutboxEventMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 017J-B 手工集成（Outbox 启用）：秒杀券创建同事务 MySQL+Outbox、
 * Router 驱动 Redis 原子初始化、永久 marker、响应丢失重试不重置实时库存、
 * marker 冲突与部分旧 Key fail-closed、Outbox 唯一冲突整体回滚。
 *
 * <p>默认不进 Surefire；显式运行需设置专用隔离环境变量且 schema 为 linklife_it_017g_*。</p>
 */
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_INTEGRATION_ENABLED", matches = "(?i)true")
@EnabledIfEnvironmentVariable(named = "LINKLIFE_MANUAL_CONFIRM_ISOLATED", matches = "(?i)true")
@ManualIntegrationEnvironment.FullIsolationRequired
  @SpringBootTest(classes = com.linklife.transaction.TransactionServiceApplication.class, properties = {
        "linklife.trade.outbox.enabled=true",
        "linklife.trade.outbox.initial-delay-ms=600000",
        "linklife.trade.outbox.scan-delay-ms=600000"})
class SeckillVoucherOutboxManualIntegration extends Stage3E017gIntegrationSupport {

    @Resource
    private VoucherServiceImpl voucherService;

    @Resource
    private OutboxEventMapper outboxEventMapper;

    @Resource
    private OutboxProperties outboxProperties;

    @Resource
    private OutboxEventRouter outboxEventRouter;

    @Resource
    private SeckillVoucherInitializeAdapter initializeAdapter;

    private final List<Long> createdVoucherIds = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (Long voucherId : createdVoucherIds) {
            jdbcTemplate.update("DELETE FROM tb_outbox_event WHERE aggregate_id = ?", voucherId);
            jdbcTemplate.update("DELETE FROM tb_seckill_voucher WHERE voucher_id = ?", voucherId);
            jdbcTemplate.update("DELETE FROM tb_voucher WHERE id = ?", voucherId);
            stringRedisTemplate.delete(stockKey(voucherId));
            stringRedisTemplate.delete("transaction:seckill:begin:" + voucherId);
            stringRedisTemplate.delete("transaction:seckill:end:" + voucherId);
            stringRedisTemplate.delete("transaction:seckill:init:marker:" + voucherId);
        }
        createdVoucherIds.clear();
    }

    private Voucher newVoucher(int stock) {
        Voucher voucher = new Voucher();
        voucher.setShopId(1L);
        voucher.setTitle("集成测试秒杀券-" + UUID.randomUUID().toString().substring(0, 8));
        voucher.setSubTitle("sub");
        voucher.setRules("rules");
        voucher.setPayValue(100L);
        voucher.setActualValue(100L);
        voucher.setType(1);
        voucher.setStatus(1);
        voucher.setStock(stock);
        voucher.setBeginTime(LocalDateTime.now().plusHours(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        voucher.setEndTime(LocalDateTime.now().plusDays(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        return voucher;
    }

    private long createAndTrack(int stock) {
        Voucher voucher = newVoucher(stock);
        voucherService.addSeckillVoucher(voucher);
        assertThat(voucher.getId()).isNotNull();
        createdVoucherIds.add(voucher.getId());
        return voucher.getId();
    }

    private OutboxPollingService newPoller() {
        OutboxPollingService poller = new OutboxPollingService();
        ReflectionTestUtils.setField(poller, "outboxEventMapper", outboxEventMapper);
        ReflectionTestUtils.setField(poller, "outboxProperties", outboxProperties);
        ReflectionTestUtils.setField(poller, "outboxEventHandler", outboxEventRouter);
        return poller;
    }

    private String markerField(long voucherId, String field) {
        Object value = stringRedisTemplate.opsForHash()
                .get("transaction:seckill:init:marker:" + voucherId, field);
        return value == null ? null : String.valueOf(value);
    }

    private void assertMarkerComplete(long voucherId, String eventId, String businessKey,
                                      int initialStock, long begin, long end) {
        assertThat(markerField(voucherId, "state")).isEqualTo("done");
        assertThat(markerField(voucherId, "voucherId")).isEqualTo(String.valueOf(voucherId));
        assertThat(markerField(voucherId, "initialStock")).isEqualTo(String.valueOf(initialStock));
        assertThat(markerField(voucherId, "beginEpochMillis")).isEqualTo(String.valueOf(begin));
        assertThat(markerField(voucherId, "endEpochMillis")).isEqualTo(String.valueOf(end));
        assertThat(markerField(voucherId, "eventId")).isEqualTo(eventId);
        assertThat(markerField(voucherId, "businessKey")).isEqualTo(businessKey);
        assertThat(markerField(voucherId, "handledAt")).isNotNull().isNotBlank();
        assertThat(markerField(voucherId, "eventVersion")).isEqualTo("1");
        assertThat(stringRedisTemplate.getExpire("transaction:seckill:init:marker:" + voucherId)).isEqualTo(-1L);
    }

    @Test
    void createCommitsMySqlAndPendingOutboxWithoutRedis() {
        int stock = 10;
        long voucherId = createAndTrack(stock);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher WHERE id = ?", Integer.class, voucherId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, voucherId))
                .isEqualTo(1);
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM tb_outbox_event WHERE business_key = ?",
                String.class, "SECKILL_VOUCHER:CREATED:" + voucherId + ":V1");
        assertThat(status).isEqualTo("PENDING");
        assertThat(stringRedisTemplate.hasKey(stockKey(voucherId))).isFalse();
        assertThat(stringRedisTemplate.hasKey("transaction:seckill:begin:" + voucherId)).isFalse();
        assertThat(stringRedisTemplate.hasKey("transaction:seckill:end:" + voucherId)).isFalse();
        assertThat(stringRedisTemplate.hasKey("transaction:seckill:init:marker:" + voucherId)).isFalse();
    }

    @Test
    void outboxBusinessKeyConflictRollsBackWholeCreation() {
        long expectedVoucherId = 8_000_000_000L;
        // 清理可能的历史残留，保证预置唯一冲突行可插入
        jdbcTemplate.update("DELETE FROM tb_outbox_event WHERE business_key = ?",
                "SECKILL_VOUCHER:CREATED:" + expectedVoucherId + ":V1");
        jdbcTemplate.update("ALTER TABLE tb_voucher AUTO_INCREMENT = " + expectedVoucherId);
        String businessKey = "SECKILL_VOUCHER:CREATED:" + expectedVoucherId + ":V1";
        jdbcTemplate.update("INSERT INTO tb_outbox_event "
                        + "(event_id, business_key, aggregate_type, aggregate_id, event_type, event_version, "
                        + "payload, status, retry_count, next_retry_time, created_time, updated_time) "
                        + "VALUES (?, ?, 'SECKILL_VOUCHER', ?, 'SECKILL_VOUCHER_CREATED', 1, '{}', 'SUCCESS', 0, NOW(), NOW(), NOW())",
                "pre-" + UUID.randomUUID(), businessKey, expectedVoucherId);

        assertThatThrownBy(() -> voucherService.addSeckillVoucher(newVoucher(5)))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_voucher WHERE id = ?", Integer.class, expectedVoucherId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id = ?", Integer.class, expectedVoucherId))
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tb_outbox_event WHERE business_key = ?", Integer.class, businessKey))
                .isEqualTo(1);
        jdbcTemplate.update("DELETE FROM tb_outbox_event WHERE business_key = ?", businessKey);
    }

    @Test
    void routerDrivesAtomicInitializationAndOutboxSuccess() {
        int stock = 20;
        long voucherId = createAndTrack(stock);
        String businessKey = "SECKILL_VOUCHER:CREATED:" + voucherId + ":V1";
        String eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM tb_outbox_event WHERE business_key = ?", String.class, businessKey);

        OutboxPollResult pollResult = newPoller().pollDueEvents();
        assertThat(pollResult.succeeded()).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tb_outbox_event WHERE business_key = ?", String.class, businessKey))
                .isEqualTo("SUCCESS");
        long begin = LocalDateTime.now().plusHours(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = LocalDateTime.now().plusDays(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo(String.valueOf(stock));
        assertThat(stringRedisTemplate.opsForValue().get("transaction:seckill:begin:" + voucherId))
                .isEqualTo(String.valueOf(begin));
        assertThat(stringRedisTemplate.opsForValue().get("transaction:seckill:end:" + voucherId))
                .isEqualTo(String.valueOf(end));
        assertMarkerComplete(voucherId, eventId, businessKey, stock, begin, end);
    }

    @Test
    void responseLossRetryDoesNotResetLiveStock() {
        int stock = 15;
        long voucherId = createAndTrack(stock);
        String businessKey = "SECKILL_VOUCHER:CREATED:" + voucherId + ":V1";
        String eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM tb_outbox_event WHERE business_key = ?", String.class, businessKey);

        assertThat(newPoller().pollDueEvents().succeeded()).isEqualTo(1);
        long begin = LocalDateTime.now().plusHours(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = LocalDateTime.now().plusDays(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

        // 模拟秒杀准入已发生：Redis stock 从 initialStock 下降
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "7");

        SeckillVoucherInitializeResult retry = initializeAdapter.initialize(
                new SeckillVoucherInitializeCommand(voucherId, stock, begin, end,
                        eventId, businessKey, LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), 1));

        assertThat(retry.outcome()).isEqualTo(SeckillVoucherInitializeResult.InitializeOutcome.SUCCESS);
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId)))
                .as("实时库存不得被重置为 initialStock").isEqualTo("7");
    }

    @Test
    void markerIdentityConflictAndPartialPreexistingKeysFailClosed() {
        int stock = 8;
        long voucherId = createAndTrack(stock);
        String businessKey = "SECKILL_VOUCHER:CREATED:" + voucherId + ":V1";
        String eventId = jdbcTemplate.queryForObject(
                "SELECT event_id FROM tb_outbox_event WHERE business_key = ?", String.class, businessKey);
        long begin = LocalDateTime.now().plusHours(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long end = LocalDateTime.now().plusDays(2).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertThat(newPoller().pollDueEvents().succeeded()).isEqualTo(1);

        // marker 身份冲突：同一 marker 已存在但 eventId 不同
        stringRedisTemplate.opsForHash().put("transaction:seckill:init:marker:" + voucherId,
                "eventId", "other-event");
        SeckillVoucherInitializeResult conflict = initializeAdapter.initialize(
                new SeckillVoucherInitializeCommand(voucherId, stock, begin, end,
                        eventId, businessKey, LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), 1));
        assertThat(conflict.outcome()).isEqualTo(SeckillVoucherInitializeResult.InitializeOutcome.FATAL_FAILURE);
        assertThat(conflict.errorCode()).isEqualTo("SECKILL_INIT_MARKER_IDENTITY_CONFLICT");

        // 部分旧 Key 预存在且 marker 不存在：PREEXISTING_STATE_CONFLICT，不覆盖不删除
        stringRedisTemplate.delete("transaction:seckill:init:marker:" + voucherId);
        stringRedisTemplate.opsForValue().set(stockKey(voucherId), "3");
        SeckillVoucherInitializeResult preexisting = initializeAdapter.initialize(
                new SeckillVoucherInitializeCommand(voucherId, stock, begin, end,
                        eventId, businessKey, LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS), 1));
        assertThat(preexisting.outcome()).isEqualTo(SeckillVoucherInitializeResult.InitializeOutcome.FATAL_FAILURE);
        assertThat(preexisting.errorCode()).isEqualTo("SECKILL_INIT_PREEXISTING_STATE_CONFLICT");
        assertThat(stringRedisTemplate.opsForValue().get(stockKey(voucherId))).isEqualTo("3");
    }
}
