package com.linklife.trade.application;

import com.baomidou.mybatisplus.extension.conditions.update.UpdateChainWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.promotion.entity.SeckillVoucher;
import com.linklife.promotion.service.ISeckillVoucherService;
import com.linklife.shared.outbox.OutboxPublishCommand;
import com.linklife.shared.outbox.OutboxPublisher;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.timeout.OrderTimeoutProperties;
import com.linklife.trade.lifecycle.timeout.OrderTimeoutRocketMqProperties;
import com.linklife.trade.mapper.VoucherOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * VoucherOrderTransactionalService 单元测试：用户订单锁、Spring 事务查重/条件扣库存/保存订单、
 * 唯一约束竞争幂等确认与 MySQL 8 严格模式兼容性。不依赖真实 Redis/MySQL。
 */
class VoucherOrderTransactionalServiceTest {

    @BeforeAll
    static void initMybatisPlusTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), VoucherOrder.class);
    }

    private VoucherOrderTransactionalService service;
    private ISeckillVoucherService seckillService;
    private RedissonClient redissonClient;
    private RLock lock;
    private VoucherOrderMapper orderMapper;
    private PlatformTransactionManager txManager;
    private OutboxPublisher outboxPublisher;
    private OrderTimeoutRocketMqProperties rocketMqProperties;
    private OrderTimeoutProperties timeoutProperties;

    @BeforeEach
    void setUp() {
        service = new VoucherOrderTransactionalService();
        seckillService = mock(ISeckillVoucherService.class);
        redissonClient = mock(RedissonClient.class);
        lock = mock(RLock.class);
        orderMapper = mock(VoucherOrderMapper.class);
        txManager = mock(PlatformTransactionManager.class);
        outboxPublisher = mock(OutboxPublisher.class);
        rocketMqProperties = new OrderTimeoutRocketMqProperties();
        timeoutProperties = new OrderTimeoutProperties();
        TransactionStatus txStatus = mock(TransactionStatus.class);

        when(redissonClient.getLock("transaction:lock:order:1")).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(orderMapper.selectOne(any())).thenReturn(null);
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        when(txStatus.isNewTransaction()).thenReturn(true);

        TransactionTemplate transactionTemplate = new TransactionTemplate(txManager);
        ReflectionTestUtils.setField(service, "voucherOrderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillService);
        ReflectionTestUtils.setField(service, "redissonClient", redissonClient);
        ReflectionTestUtils.setField(service, "transactionTemplate", transactionTemplate);
        ReflectionTestUtils.setField(service, "outboxPublisher", outboxPublisher);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper().findAndRegisterModules()
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
        ReflectionTestUtils.setField(service, "rocketMqProperties", rocketMqProperties);
        ReflectionTestUtils.setField(service, "orderTimeoutProperties", timeoutProperties);
    }

    private void stubStockUpdate(boolean success) {
        UpdateChainWrapper<SeckillVoucher> updateWrapper = mock(UpdateChainWrapper.class);
        when(seckillService.update()).thenReturn(updateWrapper);
        when(updateWrapper.setSql(anyString())).thenReturn(updateWrapper);
        when(updateWrapper.eq(anyString(), any())).thenReturn(updateWrapper);
        when(updateWrapper.gt(anyString(), any())).thenReturn(updateWrapper);
        when(updateWrapper.update()).thenReturn(success);
    }

    private VoucherOrder order() {
        VoucherOrder order = new VoucherOrder();
        order.setId(1001L);
        order.setUserId(1L);
        order.setVoucherId(2L);
        return order;
    }

    @Test
    void sameOrderIdIsIdempotentWithoutStockDeduction() {
        VoucherOrder existing = new VoucherOrder();
        existing.setId(1001L);
        when(orderMapper.selectOne(any())).thenReturn(existing);

        VoucherOrderTransactionalService.ProcessResult result = service.process(order());

        assertThat(result).isEqualTo(VoucherOrderTransactionalService.ProcessResult.IDEMPOTENT_SAME_ORDER);
        verify(seckillService, never()).update();
        verify(txManager).commit(any(TransactionStatus.class));
    }

    @Test
    void uniqueConstraintRaceRollsBackThenConfirmsExistingOrder() {
        VoucherOrder existing = new VoucherOrder();
        existing.setId(1001L);
        when(orderMapper.selectOne(any())).thenReturn(null, existing);
        stubStockUpdate(true);
        when(orderMapper.insert(any(VoucherOrder.class)))
                .thenThrow(new DuplicateKeyException("duplicate uk_user_voucher"));

        VoucherOrderTransactionalService.ProcessResult result = service.process(order());

        assertThat(result).isEqualTo(VoucherOrderTransactionalService.ProcessResult.IDEMPOTENT_SAME_ORDER);
        verify(txManager).rollback(any(TransactionStatus.class));
        verify(txManager, never()).commit(any(TransactionStatus.class));
    }

    @Test
    void conflictingExistingOrderReturnsConflictWithoutStockDeductionOrInsert() {
        VoucherOrder existing = new VoucherOrder();
        existing.setId(9999L);
        when(orderMapper.selectOne(any())).thenReturn(existing);

        VoucherOrderTransactionalService.ProcessResult result = service.process(order());

        assertThat(result).isEqualTo(VoucherOrderTransactionalService.ProcessResult.CONFLICTING_EXISTING_ORDER);
        verify(seckillService, never()).update();
        verify(orderMapper, never()).insert(any(VoucherOrder.class));
        verify(txManager).commit(any(TransactionStatus.class));
    }

    @Test
    void uniqueConstraintRaceWithDifferentOrderIdReturnsConflict() {
        VoucherOrder existing = new VoucherOrder();
        existing.setId(9999L);
        when(orderMapper.selectOne(any())).thenReturn(null, existing);
        stubStockUpdate(true);
        when(orderMapper.insert(any(VoucherOrder.class)))
                .thenThrow(new DuplicateKeyException("duplicate uk_user_voucher"));

        VoucherOrderTransactionalService.ProcessResult result = service.process(order());

        assertThat(result).isEqualTo(VoucherOrderTransactionalService.ProcessResult.CONFLICTING_EXISTING_ORDER);
        verify(txManager).rollback(any(TransactionStatus.class));
    }

    @Test
    void otherIntegrityExceptionsAreNotSwallowed() {
        stubStockUpdate(true);
        when(orderMapper.insert(any(VoucherOrder.class)))
                .thenThrow(new DataIntegrityViolationException("other constraint"));

        assertThatThrownBy(() -> service.process(order()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("other constraint");

        verify(txManager).rollback(any(TransactionStatus.class));
    }

    @Test
    void insertAffectsZeroRowsRollsBack() {
        stubStockUpdate(true);
        when(orderMapper.insert(any(VoucherOrder.class))).thenReturn(0);

        assertThatThrownBy(() -> service.process(order()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("订单保存失败");

        verify(txManager).rollback(any(TransactionStatus.class));
        verify(txManager, never()).commit(any(TransactionStatus.class));
    }

    @Test
    void databaseExceptionPropagatesAndRollsBack() {
        stubStockUpdate(true);
        when(orderMapper.insert(any(VoucherOrder.class)))
                .thenThrow(new RuntimeException("db unavailable"));

        assertThatThrownBy(() -> service.process(order()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db unavailable");

        verify(txManager).rollback(any(TransactionStatus.class));
    }

    @Test
    void lockNotAcquiredThrowsWithoutUnlock() {
        when(lock.tryLock()).thenReturn(false);

        assertThatThrownBy(() -> service.process(order()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未获取到用户订单锁");

        verify(lock, never()).unlock();
        verify(lock, never()).isHeldByCurrentThread();
        verify(txManager, never()).getTransaction(any());
    }

    @Test
    void lockHeldByOtherThreadIsNotUnlocked() {
        when(lock.isHeldByCurrentThread()).thenReturn(false);
        VoucherOrder existing = new VoucherOrder();
        existing.setId(1001L);
        when(orderMapper.selectOne(any())).thenReturn(existing);

        service.process(order());

        verify(lock, never()).unlock();
    }

    @Test
    void uniqueConstraintExistsInSchemaAndUpgradeScript() throws Exception {
        String schema = StreamUtils.copyToString(
        new ClassPathResource("db/schema.sql").getInputStream(), StandardCharsets.UTF_8);
        assertThat(schema).contains("uk_user_voucher");

        String upgrade = StreamUtils.copyToString(
                new ClassPathResource("db/upgrade/001_add_voucher_order_unique_constraint.sql").getInputStream(),
                StandardCharsets.UTF_8);
        assertThat(upgrade).contains("uk_user_voucher");
    }

    @Test
    void seckillVoucherSchemaIsMySql8StrictModeCompatible() throws Exception {
        String schema = StreamUtils.copyToString(
        new ClassPathResource("db/schema.sql").getInputStream(), StandardCharsets.UTF_8);

        assertThat(schema).doesNotContain("0000-00-00");
        assertThat(schema).contains("`begin_time` timestamp NOT NULL COMMENT '生效时间'");
        assertThat(schema).contains("`end_time` timestamp NOT NULL COMMENT '失效时间'");
        assertThat(schema).doesNotContain("begin_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP");
        assertThat(schema).doesNotContain("end_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP");
        assertThat(schema).contains("uk_user_voucher");
    }

    @Test
    void newOrderDeductsStockInsertsCommitsAndUnlocks() {
        UpdateChainWrapper<SeckillVoucher> updateWrapper = mock(UpdateChainWrapper.class);
        when(seckillService.update()).thenReturn(updateWrapper);
        when(updateWrapper.setSql(anyString())).thenReturn(updateWrapper);
        when(updateWrapper.eq(anyString(), any())).thenReturn(updateWrapper);
        when(updateWrapper.gt(anyString(), any())).thenReturn(updateWrapper);
        when(updateWrapper.update()).thenReturn(true);
        when(orderMapper.insert(any(VoucherOrder.class))).thenReturn(1);

        VoucherOrderTransactionalService.ProcessResult result = service.process(order());

        assertThat(result).isEqualTo(VoucherOrderTransactionalService.ProcessResult.CREATED);
        verify(updateWrapper).setSql("stock = stock - 1");
        verify(updateWrapper).eq("voucher_id", 2L);
        verify(updateWrapper).gt("stock", 0);
        verify(orderMapper).insert(any(VoucherOrder.class));
        verify(txManager).commit(any(TransactionStatus.class));
        verify(txManager, never()).rollback(any(TransactionStatus.class));
        verify(lock).unlock();
    }

    @Test
    void stockDeductionFailureRollsBackWithoutInsertAndUnlocks() {
        stubStockUpdate(false);

        assertThatThrownBy(() -> service.process(order()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("库存不足");

        verify(orderMapper, never()).insert(any(VoucherOrder.class));
        verify(txManager).rollback(any(TransactionStatus.class));
        verify(txManager, never()).commit(any(TransactionStatus.class));
        verify(lock).unlock();
    }

    @Test
    void duplicateKeyWithoutExistingOrderFailsClosed() {
        when(orderMapper.selectOne(any())).thenReturn(null, null);
        stubStockUpdate(true);
        DuplicateKeyException duplicate = new DuplicateKeyException("duplicate uk_user_voucher");
        when(orderMapper.insert(any(VoucherOrder.class))).thenThrow(duplicate);

        assertThatThrownBy(() -> service.process(order()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("唯一约束冲突但订单不存在")
                .hasCause(duplicate);

        verify(txManager).rollback(any(TransactionStatus.class));
        verify(txManager, never()).commit(any(TransactionStatus.class));
        verify(lock).unlock();
    }

    @Test
    void mqEnabledCreatesExactlyOneFrozenTimeoutIntentInSameTransaction() {
        rocketMqProperties.setEnabled(true);
        timeoutProperties.setPaymentTimeout(Duration.ofMinutes(15));
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 18, 10, 0);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(createdAt.atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                        ZoneId.of("Asia/Shanghai")));
        stubStockUpdate(true);
        when(orderMapper.insert(any(VoucherOrder.class))).thenReturn(1);

        assertThat(service.process(order()))
                .isEqualTo(VoucherOrderTransactionalService.ProcessResult.CREATED);

        ArgumentCaptor<VoucherOrder> orderCaptor = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(orderMapper).insert(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(1);
        assertThat(orderCaptor.getValue().getCreateTime()).isEqualTo(createdAt);
        assertThat(orderCaptor.getValue().getPaymentDueAt())
                .isEqualTo(Instant.parse("2026-08-18T02:15:00Z"));
        assertThat(orderCaptor.getValue().getUpdateTime()).isEqualTo(createdAt);

        ArgumentCaptor<OutboxPublishCommand> commandCaptor =
                ArgumentCaptor.forClass(OutboxPublishCommand.class);
        verify(outboxPublisher).publish(commandCaptor.capture());
        OutboxPublishCommand command = commandCaptor.getValue();
        assertThat(command.eventType()).isEqualTo("ORDER_PAYMENT_TIMEOUT_CHECK");
        assertThat(command.businessKey())
                .isEqualTo("VOUCHER_ORDER:PAYMENT_TIMEOUT_CHECK:1001:V1");
        assertThat(command.now()).isEqualTo(createdAt);
        assertThat(command.payload()).contains("\"createdAt\":\"2026-08-18T10:00:00\"")
                .contains("\"createdAtInstant\":\"2026-08-18T02:00:00Z\"")
                .contains("\"dueAt\":\"2026-08-18T02:15:00Z\"");
        verify(txManager).commit(any(TransactionStatus.class));
    }

    @Test
    void mqDisabledStillFreezesOrderDeadlineButDoesNotCreateMqIntent() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 18, 10, 0);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(Instant.parse("2026-08-18T02:00:00Z"), ZoneId.of("UTC")));
        stubStockUpdate(true);
        when(orderMapper.insert(any(VoucherOrder.class))).thenReturn(1);

        assertThat(service.process(order()))
                .isEqualTo(VoucherOrderTransactionalService.ProcessResult.CREATED);

        ArgumentCaptor<VoucherOrder> captor = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(orderMapper).insert(captor.capture());
        assertThat(captor.getValue().getCreateTime()).isEqualTo(createdAt);
        assertThat(captor.getValue().getPaymentDueAt())
                .isEqualTo(Instant.parse("2026-08-18T02:15:00Z"));
        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    void sameOrderDuplicateDoesNotCreateSecondTimeoutIntent() {
        rocketMqProperties.setEnabled(true);
        VoucherOrder existing = new VoucherOrder();
        existing.setId(1001L);
        when(orderMapper.selectOne(any())).thenReturn(existing);

        assertThat(service.process(order()))
                .isEqualTo(VoucherOrderTransactionalService.ProcessResult.IDEMPOTENT_SAME_ORDER);

        verify(outboxPublisher, never()).publish(any());
    }

    @Test
    void timeoutIntentFailureRollsBackOrderAndStockTransaction() {
        rocketMqProperties.setEnabled(true);
        stubStockUpdate(true);
        when(orderMapper.insert(any(VoucherOrder.class))).thenReturn(1);
        RuntimeException writeFailure = new RuntimeException("outbox write failed");
        org.mockito.Mockito.doThrow(writeFailure).when(outboxPublisher).publish(any());

        assertThatThrownBy(() -> service.process(order()))
                .isSameAs(writeFailure);

        verify(txManager).rollback(any(TransactionStatus.class));
        verify(txManager, never()).commit(any(TransactionStatus.class));
    }
}
