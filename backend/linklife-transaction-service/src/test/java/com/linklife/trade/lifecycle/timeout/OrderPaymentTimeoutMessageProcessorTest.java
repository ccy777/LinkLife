package com.linklife.trade.lifecycle.timeout;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.linklife.trade.application.OrderCloseTransactionService;
import com.linklife.trade.dto.OrderPaymentTimeoutEventPayload;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.lifecycle.close.OrderCloseCommand;
import com.linklife.trade.lifecycle.close.OrderCloseResult;
import com.linklife.trade.mapper.VoucherOrderMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderPaymentTimeoutMessageProcessorTest {

    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 18, 10, 0);
    private static final Instant CREATED_AT_INSTANT = Instant.parse("2026-08-18T02:00:00Z");
    private static final Instant DUE_AT = Instant.parse("2026-08-18T02:15:00Z");
    private OrderPaymentTimeoutMessageProcessor processor;
    private VoucherOrderMapper mapper;
    private OrderCloseTransactionService closeService;

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), VoucherOrder.class);
    }

    @BeforeEach
    void setUp() {
        processor = new OrderPaymentTimeoutMessageProcessor();
        mapper = mock(VoucherOrderMapper.class);
        closeService = mock(OrderCloseTransactionService.class);
        ReflectionTestUtils.setField(processor, "voucherOrderMapper", mapper);
        ReflectionTestUtils.setField(processor, "orderCloseTransactionService", closeService);
        ReflectionTestUtils.setField(processor, "orderTimeoutProperties", new OrderTimeoutProperties());
        ReflectionTestUtils.setField(processor, "clock", Clock.fixed(
                CREATED_AT.plusMinutes(15).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                ZoneId.of("Asia/Shanghai")));
    }

    @Test
    void unpaidAndDueDelegatesToUnifiedCloseWithAbsoluteDueAtCutoff() {
        when(mapper.selectOne(any())).thenReturn(order(VoucherOrderStatus.UNPAID));
        when(closeService.close(any())).thenReturn(OrderCloseResult.CLOSED);

        assertThat(processor.process(payload()))
                .isEqualTo(OrderPaymentTimeoutMessageProcessor.ProcessResult.CLOSED);

        ArgumentCaptor<OrderCloseCommand> command = ArgumentCaptor.forClass(OrderCloseCommand.class);
        verify(closeService).close(command.capture());
        assertThat(command.getValue().dueAtCutoff()).isEqualTo(DUE_AT);
        assertThat(command.getValue().now()).isEqualTo(CREATED_AT.plusMinutes(15));
    }

    @Test
    void earlyMessageNeverCloses() {
        ReflectionTestUtils.setField(processor, "clock", Clock.fixed(
                CREATED_AT.plusMinutes(14).atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                ZoneId.of("Asia/Shanghai")));
        when(mapper.selectOne(any())).thenReturn(order(VoucherOrderStatus.UNPAID));

        assertThat(processor.process(payload()))
                .isEqualTo(OrderPaymentTimeoutMessageProcessor.ProcessResult.TOO_EARLY);
        verify(closeService, never()).close(any());
    }

    @Test
    void historicalPayloadDoesNotDependOnCurrentConfiguredZone() {
        OrderTimeoutProperties changedConfiguration = new OrderTimeoutProperties();
        changedConfiguration.setZoneId("UTC");
        ReflectionTestUtils.setField(processor, "orderTimeoutProperties", changedConfiguration);
        ReflectionTestUtils.setField(processor, "clock", Clock.fixed(DUE_AT, ZoneId.of("UTC")));
        when(mapper.selectOne(any())).thenReturn(order(VoucherOrderStatus.UNPAID));
        when(closeService.close(any())).thenReturn(OrderCloseResult.CLOSED);

        assertThat(processor.process(payload()))
                .isEqualTo(OrderPaymentTimeoutMessageProcessor.ProcessResult.CLOSED);
        verify(closeService).close(any());
    }

    @ParameterizedTest
    @EnumSource(value = VoucherOrderStatus.class,
            names = {"PAID", "USED", "REFUNDING", "REFUNDED"})
    void paidAndOtherTerminalStatesAreNoOp(VoucherOrderStatus status) {
        when(mapper.selectOne(any())).thenReturn(order(status));
        assertThat(processor.process(payload()))
                .isEqualTo(OrderPaymentTimeoutMessageProcessor.ProcessResult.NOT_CLOSABLE);
        verify(closeService, never()).close(any());
    }

    @Test
    void canceledIsIdempotentNoOp() {
        when(mapper.selectOne(any())).thenReturn(order(VoucherOrderStatus.CANCELED));
        assertThat(processor.process(payload()))
                .isEqualTo(OrderPaymentTimeoutMessageProcessor.ProcessResult.ALREADY_CANCELED);
        verify(closeService, never()).close(any());
    }

    @Test
    void missingIdentityMismatchAndUnknownStatusFailClosed() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertThat(processor.process(payload()))
                .isEqualTo(OrderPaymentTimeoutMessageProcessor.ProcessResult.NOT_FOUND);

        VoucherOrder mismatch = order(VoucherOrderStatus.UNPAID);
        mismatch.setUserId(999L);
        when(mapper.selectOne(any())).thenReturn(mismatch);
        assertThat(processor.process(payload()))
                .isEqualTo(OrderPaymentTimeoutMessageProcessor.ProcessResult.IDENTITY_MISMATCH);

        VoucherOrder invalid = order(VoucherOrderStatus.UNPAID);
        invalid.setStatus(99);
        when(mapper.selectOne(any())).thenReturn(invalid);
        assertThat(processor.process(payload()))
                .isEqualTo(OrderPaymentTimeoutMessageProcessor.ProcessResult.INVALID_ORDER_FACT);
        verify(closeService, never()).close(any());
    }

    @Test
    void databaseUnavailableIsRetryableAndNeverPretendsSuccess() {
        when(mapper.selectOne(any())).thenThrow(new DataAccessResourceFailureException("db down"));
        assertThatThrownBy(() -> processor.process(payload()))
                .isInstanceOf(OrderPaymentTimeoutMessageProcessor.RetryableTimeoutMessageException.class);
        verify(closeService, never()).close(any());
    }

    private OrderPaymentTimeoutEventPayload payload() {
        return new OrderPaymentTimeoutEventPayload(
                "event-1", 1, 1001L, 11L, 22L, CREATED_AT,
                CREATED_AT_INSTANT, DUE_AT);
    }

    private VoucherOrder order(VoucherOrderStatus status) {
        return new VoucherOrder().setId(1001L).setUserId(11L).setVoucherId(22L)
                .setStatus(status.getCode()).setCreateTime(CREATED_AT).setPaymentDueAt(DUE_AT);
    }
}
