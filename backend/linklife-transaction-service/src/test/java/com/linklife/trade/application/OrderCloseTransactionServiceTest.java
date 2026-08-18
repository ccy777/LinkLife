package com.linklife.trade.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linklife.promotion.entity.SeckillVoucher;
import com.linklife.promotion.service.ISeckillVoucherService;
import com.linklife.trade.dto.OrderClosedEventPayload;
import com.linklife.trade.entity.OrderStatusLog;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.lifecycle.close.OrderCloseCommand;
import com.linklife.trade.lifecycle.close.OrderCloseReasonCode;
import com.linklife.trade.lifecycle.close.OrderCloseResult;
import com.linklife.trade.lifecycle.close.OrderCloseTriggerType;
import com.linklife.trade.mapper.OrderStatusLogMapper;
import com.linklife.trade.mapper.OutboxEventMapper;
import com.linklife.trade.mapper.VoucherOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderCloseTransactionService 单元测试：统一关闭事务的 CAS 条件与内容、库存+1、状态日志、
 * Outbox 事件、0 行判定、fail-closed 与异常传播。验证真实 QueryWrapper/UpdateWrapper SQL 条件与实体字段。
 * Mockito 单元测试只证明调用顺序、条件与异常传播，不声称真实数据库事务回滚已验证
 * （真实回滚与唯一约束由后续 017G 隔离 MySQL 集成验证）。
 */
class OrderCloseTransactionServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 6, 10, 0, 0);
    private static final Instant FIXED_DUE_AT_CUTOFF = FIXED_NOW.toInstant(ZoneOffset.UTC);

    private OrderCloseTransactionService service;
    private VoucherOrderMapper orderMapper;
    private OrderStatusLogMapper logMapper;
    private OutboxEventMapper outboxMapper;
    private ISeckillVoucherService seckillService;
    private BaseMapper<SeckillVoucher> stockBaseMapper;
    private PlatformTransactionManager txManager;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                VoucherOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SeckillVoucher.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OrderStatusLog.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OutboxEvent.class);

        service = new OrderCloseTransactionService();
        orderMapper = mock(VoucherOrderMapper.class);
        logMapper = mock(OrderStatusLogMapper.class);
        outboxMapper = mock(OutboxEventMapper.class);
        seckillService = mock(ISeckillVoucherService.class);
        stockBaseMapper = mock(BaseMapper.class);
        txManager = mock(PlatformTransactionManager.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        TransactionStatus txStatus = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(txStatus);
        when(txStatus.isNewTransaction()).thenReturn(true);
        when(seckillService.getBaseMapper()).thenReturn(stockBaseMapper);

        ReflectionTestUtils.setField(service, "voucherOrderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "orderStatusLogMapper", logMapper);
        ReflectionTestUtils.setField(service, "outboxEventMapper", outboxMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillService);
        ReflectionTestUtils.setField(service, "transactionTemplate", new TransactionTemplate(txManager));
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
    }

    private VoucherOrder unpaidOrder() {
        VoucherOrder order = new VoucherOrder();
        order.setId(1001L);
        order.setUserId(1L);
        order.setVoucherId(2L);
        order.setStatus(VoucherOrderStatus.UNPAID.getCode());
        order.setCreateTime(FIXED_NOW.minusMinutes(16));
        order.setPaymentDueAt(FIXED_DUE_AT_CUTOFF.minusSeconds(60));
        return order;
    }

    private OrderCloseCommand userCancelCommand() {
        return new OrderCloseCommand(1001L, 1L, OrderCloseTriggerType.USER_CANCEL, null,
                OrderCloseReasonCode.USER_CANCEL, FIXED_NOW);
    }

    private OrderCloseCommand timeoutCloseCommand() {
        return new OrderCloseCommand(1001L, null, OrderCloseTriggerType.TIMEOUT_CLOSE, FIXED_DUE_AT_CUTOFF,
                OrderCloseReasonCode.TIMEOUT_EXPIRED, FIXED_NOW);
    }

    private void stubSuccessPath() {
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder());
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(stockBaseMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(logMapper.insert(any(OrderStatusLog.class))).thenReturn(1);
        when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(1);
    }

    @Test
    void userCancelSuccessWritesCasStockLogAndOutbox() throws Exception {
        stubSuccessPath();

        OrderCloseResult result = service.close(userCancelCommand());

        assertThat(result).isEqualTo(OrderCloseResult.CLOSED);

        ArgumentCaptor<LambdaUpdateWrapper<VoucherOrder>> casCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(isNull(), casCaptor.capture());
        LambdaUpdateWrapper<VoucherOrder> cas = casCaptor.getValue();
        String casSegment = cas.getSqlSegment();
        assertThat(casSegment).contains("id").contains("user_id").contains("status");
        cas.getSqlSegment();
        assertThat(cas.getParamNameValuePairs().values())
                .contains(1001L, 1L, VoucherOrderStatus.UNPAID.getCode());
        String casSet = cas.getSqlSet();
        assertThat(casSet).contains("status").contains("update_time");
        cas.getSqlSegment();
        assertThat(cas.getParamNameValuePairs().values())
                .contains(VoucherOrderStatus.CANCELED.getCode(), FIXED_NOW);

        ArgumentCaptor<UpdateWrapper<SeckillVoucher>> stockCaptor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(stockBaseMapper).update(isNull(), stockCaptor.capture());
        UpdateWrapper<SeckillVoucher> stock = stockCaptor.getValue();
        assertThat(stock.getSqlSet()).contains("stock = stock + 1");
        assertThat(stock.getSqlSegment()).contains("voucher_id");
        stock.getSqlSegment();
        assertThat(stock.getParamNameValuePairs().values()).contains(2L);

        ArgumentCaptor<OrderStatusLog> logCaptor = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(logMapper).insert(logCaptor.capture());
        OrderStatusLog log = logCaptor.getValue();
        assertThat(log.getOrderId()).isEqualTo(1001L);
        assertThat(log.getFromStatus()).isEqualTo(VoucherOrderStatus.UNPAID.getCode());
        assertThat(log.getToStatus()).isEqualTo(VoucherOrderStatus.CANCELED.getCode());
        assertThat(log.getTriggerType()).isEqualTo("USER_CANCEL");
        assertThat(log.getOperatorType()).isEqualTo("USER");
        assertThat(log.getOperatorId()).isEqualTo(1L);
        assertThat(log.getReasonCode()).isEqualTo("USER_CANCEL");
        assertThat(log.getIdempotencyKey()).isEqualTo("ORDER_STATUS:1001:1:4");
        assertThat(log.getCreatedTime()).isEqualTo(FIXED_NOW);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxMapper).insert(outboxCaptor.capture());
        OutboxEvent event = outboxCaptor.getValue();
        assertThat(event.getBusinessKey()).isEqualTo("VOUCHER_ORDER:CLOSED:1001:V1");
        assertThat(event.getAggregateType()).isEqualTo("VOUCHER_ORDER");
        assertThat(event.getAggregateId()).isEqualTo(1001L);
        assertThat(event.getEventType()).isEqualTo("ORDER_CLOSED");
        assertThat(event.getEventVersion()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getNextRetryTime()).isEqualTo(FIXED_NOW);
        assertThat(event.getCreatedTime()).isEqualTo(FIXED_NOW);
        assertThat(event.getUpdatedTime()).isEqualTo(FIXED_NOW);
        assertThat(event.getLockToken()).isNull();
        assertThat(event.getLockedUntil()).isNull();
        assertThat(event.getProcessingStartedTime()).isNull();
        assertThat(event.getCompletedTime()).isNull();

        OrderClosedEventPayload payload = objectMapper.readValue(
                event.getPayload(), OrderClosedEventPayload.class);
        assertThat(payload.eventId()).isEqualTo(event.getEventId());
        assertThat(payload.eventVersion()).isEqualTo(1);
        assertThat(payload.orderId()).isEqualTo(1001L);
        assertThat(payload.userId()).isEqualTo(1L);
        assertThat(payload.voucherId()).isEqualTo(2L);
        assertThat(payload.toStatus()).isEqualTo(VoucherOrderStatus.CANCELED.getCode());
        assertThat(payload.triggerType()).isEqualTo("USER_CANCEL");
        assertThat(payload.closedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void timeoutCloseSuccessCasContainsCutoffAndNoUserId() {
        stubSuccessPath();

        OrderCloseResult result = service.close(timeoutCloseCommand());

        assertThat(result).isEqualTo(OrderCloseResult.CLOSED);
        ArgumentCaptor<LambdaUpdateWrapper<VoucherOrder>> casCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(orderMapper).update(isNull(), casCaptor.capture());
        LambdaUpdateWrapper<VoucherOrder> cas = casCaptor.getValue();
        String segment = cas.getSqlSegment();
        assertThat(segment).contains("id").contains("status").contains("payment_due_at");
        cas.getSqlSegment();
        assertThat(cas.getParamNameValuePairs().values())
                .contains(1001L, VoucherOrderStatus.UNPAID.getCode(), FIXED_DUE_AT_CUTOFF);
        assertThat(cas.getParamNameValuePairs().values()).doesNotContain(1L);

        ArgumentCaptor<OrderStatusLog> logCaptor = ArgumentCaptor.forClass(OrderStatusLog.class);
        verify(logMapper).insert(logCaptor.capture());
        OrderStatusLog log = logCaptor.getValue();
        assertThat(log.getTriggerType()).isEqualTo("TIMEOUT_CLOSE");
        assertThat(log.getOperatorType()).isEqualTo("SYSTEM");
        assertThat(log.getOperatorId()).isNull();
        assertThat(log.getReasonCode()).isEqualTo("TIMEOUT_EXPIRED");
    }

    @Test
    void alreadyCanceledViaZeroRowReturnsAlreadyCanceled() {
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder(), canceledOrder());
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        OrderCloseResult result = service.close(userCancelCommand());

        assertThat(result).isEqualTo(OrderCloseResult.ALREADY_CANCELED);
        verify(stockBaseMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(logMapper, never()).insert(any(OrderStatusLog.class));
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void zeroRowCurrentReadUsesForUpdateWithUserIsolation() {
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder(), canceledOrder());
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        OrderCloseResult result = service.close(userCancelCommand());

        assertThat(result).isEqualTo(OrderCloseResult.ALREADY_CANCELED);
        ArgumentCaptor<LambdaQueryWrapper<VoucherOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper, org.mockito.Mockito.times(2)).selectOne(captor.capture());
        java.util.List<LambdaQueryWrapper<VoucherOrder>> wrappers = captor.getAllValues();
        LambdaQueryWrapper<VoucherOrder> first = wrappers.get(0);
        LambdaQueryWrapper<VoucherOrder> currentRead = wrappers.get(1);

        assertThat(first.getSqlSegment()).doesNotContain("FOR UPDATE");
        first.getSqlSegment();
        assertThat(first.getParamNameValuePairs().values()).contains(1001L, 1L);

        assertThat(currentRead.getSqlSegment()).contains("FOR UPDATE");
        assertThat(currentRead.getSqlSelect()).contains("id").contains("user_id")
                .contains("voucher_id").contains("status").contains("create_time");
        currentRead.getSqlSegment();
        assertThat(currentRead.getParamNameValuePairs().values()).contains(1001L, 1L);

        verify(stockBaseMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(logMapper, never()).insert(any(OrderStatusLog.class));
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void timeoutZeroRowCurrentReadScopesByIdOnly() {
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder(), canceledOrder());
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        OrderCloseResult result = service.close(timeoutCloseCommand());

        assertThat(result).isEqualTo(OrderCloseResult.ALREADY_CANCELED);
        ArgumentCaptor<LambdaQueryWrapper<VoucherOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper, org.mockito.Mockito.times(2)).selectOne(captor.capture());
        LambdaQueryWrapper<VoucherOrder> currentRead = captor.getAllValues().get(1);
        assertThat(currentRead.getSqlSegment()).contains("FOR UPDATE");
        currentRead.getSqlSegment();
        assertThat(currentRead.getParamNameValuePairs().values()).contains(1001L);
        assertThat(currentRead.getParamNameValuePairs().values()).doesNotContain(1L);
    }

    @Test
    void currentReadSourceContractUsesDedicatedMethodAndFixedTail() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/application/OrderCloseTransactionService.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .contains("queryOrderForCurrentRead(OrderCloseCommand command)")
                .contains("private static final String CURRENT_READ_TAIL = \"FOR UPDATE\";")
                .contains("wrapper.last(CURRENT_READ_TAIL)");
    }

    @Test
    void paidViaZeroRowReturnsNotClosable() {
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder(), orderWithStatus(VoucherOrderStatus.PAID.getCode()));
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        OrderCloseResult result = service.close(userCancelCommand());

        assertThat(result).isEqualTo(OrderCloseResult.NOT_CLOSABLE);
        verify(stockBaseMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(logMapper, never()).insert(any(OrderStatusLog.class));
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void userInvisibleOrderReturnsNotFound() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        OrderCloseResult result = service.close(userCancelCommand());

        assertThat(result).isEqualTo(OrderCloseResult.NOT_FOUND);
        verify(orderMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        ArgumentCaptor<LambdaQueryWrapper<VoucherOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(orderMapper).selectOne(captor.capture());
        captor.getValue().getSqlSegment();
        assertThat(captor.getValue().getParamNameValuePairs().values()).contains(1001L, 1L);
    }

    @Test
    void timeoutOrderNotFoundReturnsNotFound() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        OrderCloseResult result = service.close(timeoutCloseCommand());

        assertThat(result).isEqualTo(OrderCloseResult.NOT_FOUND);
        verify(orderMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void casAffectedNegativeOrGreaterThanOneFailsClosed() {
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder());
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(-1);

        assertThatThrownBy(() -> service.close(userCancelCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed");

        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(2);
        assertThatThrownBy(() -> service.close(userCancelCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed");

        verify(stockBaseMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(logMapper, never()).insert(any(OrderStatusLog.class));
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void stockUpdateAffectedZeroOrGreaterThanOneFailsClosed() {
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder());
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(stockBaseMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        assertThatThrownBy(() -> service.close(userCancelCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("库存返还");

        when(stockBaseMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(2);
        assertThatThrownBy(() -> service.close(userCancelCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("库存返还");

        verify(logMapper, never()).insert(any(OrderStatusLog.class));
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void logInsertFailurePropagatesAndNoOutbox() {
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder());
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(stockBaseMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(logMapper.insert(any(OrderStatusLog.class))).thenReturn(0);

        assertThatThrownBy(() -> service.close(userCancelCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("状态日志写入失败");

        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void outboxInsertFailurePropagates() {
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder());
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(stockBaseMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(logMapper.insert(any(OrderStatusLog.class))).thenReturn(1);
        when(outboxMapper.insert(any(OutboxEvent.class))).thenReturn(0);

        assertThatThrownBy(() -> service.close(userCancelCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Outbox 事件写入失败");
    }

    @Test
    void zeroRowNeverRestoresStockLogOrOutbox() {
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder(), canceledOrder());
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        OrderCloseResult result = service.close(timeoutCloseCommand());

        assertThat(result).isEqualTo(OrderCloseResult.ALREADY_CANCELED);
        verify(stockBaseMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(logMapper, never()).insert(any(OrderStatusLog.class));
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void eventIdIsApplicationUuidAndBusinessKeyIsDeterministic() throws Exception {
        stubSuccessPath();

        service.close(userCancelCommand());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxMapper).insert(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(UUID.fromString(event.getEventId())).isNotNull();
        assertThat(event.getBusinessKey()).isEqualTo("VOUCHER_ORDER:CLOSED:1001:V1");

        OrderClosedEventPayload payload = objectMapper.readValue(
                event.getPayload(), OrderClosedEventPayload.class);
        assertThat(payload.eventId()).isEqualTo(event.getEventId());
        assertThat(payload.triggerType()).isEqualTo("USER_CANCEL");
        assertThat(payload.closedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void serviceDoesNotTouchRedisOrSremOrPoller() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/application/OrderCloseTransactionService.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("StringRedisTemplate")
                .doesNotContain("RedisConstants")
                .doesNotContain("OutboxPoller")
                .doesNotContain("RedissonClient")
                .doesNotContain("OrderStreamConsumer")
                .doesNotContain("@Scheduled");

        assertThat(Arrays.stream(OrderCloseTransactionService.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName))
                .doesNotContain(
                        "org.springframework.data.redis.core.StringRedisTemplate",
                        "org.redisson.api.RedissonClient",
                        "com.linklife.identity.security.UserHolder");
    }

    @Test
    void unknownStatusFailsClosed() {
        when(orderMapper.selectOne(any())).thenReturn(orderWithStatus(7));

        assertThatThrownBy(() -> service.close(userCancelCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed");

        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder(), orderWithStatus(7));
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);
        assertThatThrownBy(() -> service.close(userCancelCommand()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    void serviceDoesNotReadUserHolder() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/application/OrderCloseTransactionService.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("import com.linklife.identity.security.UserHolder")
                .doesNotContain("import com.linklife.trade.controller");
    }

    @Test
    void earlyCanceledOrNotClosableReturnWithoutWrites() {
        when(orderMapper.selectOne(any())).thenReturn(canceledOrder());
        assertThat(service.close(userCancelCommand())).isEqualTo(OrderCloseResult.ALREADY_CANCELED);

        when(orderMapper.selectOne(any())).thenReturn(orderWithStatus(VoucherOrderStatus.PAID.getCode()));
        assertThat(service.close(userCancelCommand())).isEqualTo(OrderCloseResult.NOT_CLOSABLE);

        verify(orderMapper, never()).update(isNull(), any(LambdaUpdateWrapper.class));
        verify(stockBaseMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(logMapper, never()).insert(any(OrderStatusLog.class));
        verify(outboxMapper, never()).insert(any(OutboxEvent.class));
    }

    @Test
    void zeroRowUnpaidButPaymentDueAtNotSatisfiedReturnsNotClosableForTimeout() {
        VoucherOrder fresh = unpaidOrder();
        fresh.setPaymentDueAt(FIXED_DUE_AT_CUTOFF.plusSeconds(60));
        when(orderMapper.selectOne(any())).thenReturn(unpaidOrder(), fresh);
        when(orderMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        OrderCloseResult result = service.close(timeoutCloseCommand());

        assertThat(result).isEqualTo(OrderCloseResult.NOT_CLOSABLE);
    }

    private VoucherOrder canceledOrder() {
        return orderWithStatus(VoucherOrderStatus.CANCELED.getCode());
    }

    private VoucherOrder orderWithStatus(int status) {
        VoucherOrder order = new VoucherOrder();
        order.setId(1001L);
        order.setUserId(1L);
        order.setVoucherId(2L);
        order.setStatus(status);
        order.setCreateTime(FIXED_NOW.minusMinutes(16));
        order.setPaymentDueAt(FIXED_DUE_AT_CUTOFF.minusSeconds(60));
        return order;
    }
}
