package com.linklife.promotion.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linklife.promotion.entity.SeckillVoucher;
import com.linklife.promotion.entity.Voucher;
import com.linklife.promotion.mapper.VoucherMapper;
import com.linklife.promotion.service.ISeckillVoucherService;
import com.linklife.shared.event.SeckillVoucherCreatedEventPayload;
import com.linklife.shared.outbox.OutboxPublishCommand;
import com.linklife.shared.outbox.OutboxPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 秒杀券创建：参数校验、同事务 Outbox 发布与事务回滚语义的单元测试。
 * 不再直接操作 Redis；Redis 初始化由 Outbox 驱动。
 */
class VoucherServiceImplTest {

    private VoucherServiceImpl service;
    private ISeckillVoucherService seckillVoucherService;
    private VoucherMapper voucherMapper;
    private OutboxPublisher outboxPublisher;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = spy(new VoucherServiceImpl());
        seckillVoucherService = mock(ISeckillVoucherService.class);
        voucherMapper = mock(VoucherMapper.class);
        outboxPublisher = mock(OutboxPublisher.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        when(voucherMapper.insert(any(Voucher.class))).thenAnswer(invocation -> {
            Voucher voucher = invocation.getArgument(0);
            if (voucher != null && voucher.getId() == null) {
                voucher.setId(100L);
            }
            return 1;
        });
        when(seckillVoucherService.save(any(SeckillVoucher.class))).thenReturn(true);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(service, "outboxPublisher", outboxPublisher);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(service, "baseMapper", voucherMapper);
    }

    private Voucher validVoucher() {
        Voucher voucher = new Voucher();
        voucher.setStock(10);
        voucher.setBeginTime(LocalDateTime.of(2026, 8, 1, 0, 0));
        voucher.setEndTime(LocalDateTime.of(2026, 8, 10, 0, 0));
        return voucher;
    }

    @Test
    void validCreationPublishesOneOutboxEvent() throws Exception {
        service.addSeckillVoucher(validVoucher());

        ArgumentCaptor<OutboxPublishCommand> captor = ArgumentCaptor.forClass(OutboxPublishCommand.class);
        verify(outboxPublisher).publish(captor.capture());
        OutboxPublishCommand command = captor.getValue();
        assertThat(command.aggregateType()).isEqualTo("SECKILL_VOUCHER");
        assertThat(command.aggregateId()).isEqualTo(100L);
        assertThat(command.eventType()).isEqualTo("SECKILL_VOUCHER_CREATED");
        assertThat(command.eventVersion()).isEqualTo(1);
        assertThat(command.businessKey()).isEqualTo("SECKILL_VOUCHER:CREATED:100:V1");
        assertThat(command.eventId()).isNotBlank();
        assertThat(command.now().getNano()).isZero();

        SeckillVoucherCreatedEventPayload payload = objectMapper.readValue(
                command.payload(), SeckillVoucherCreatedEventPayload.class);
        assertThat(payload.eventId()).isEqualTo(command.eventId());
        assertThat(payload.eventVersion()).isEqualTo(1);
        assertThat(payload.voucherId()).isEqualTo(100L);
        assertThat(payload.initialStock()).isEqualTo(10);
        assertThat(payload.beginEpochMillis()).isEqualTo(
                LocalDateTime.of(2026, 8, 1, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        assertThat(payload.endEpochMillis()).isEqualTo(
                LocalDateTime.of(2026, 8, 10, 0, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        assertThat(payload.createdAt()).isNotNull();
    }

    @Test
    void voucherIdNullAfterSaveThrowsWithoutPublish() {
        doReturn(1).when(voucherMapper).insert(any(Voucher.class));

        assertThatThrownBy(() -> service.addSeckillVoucher(validVoucher()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未生成 ID");

        verify(seckillVoucherService, never()).save(any(SeckillVoucher.class));
        verify(outboxPublisher, never()).publish(any(OutboxPublishCommand.class));
    }

    @Test
    void mainVoucherSaveFalseThrowsWithoutPublish() {
        doReturn(0).when(voucherMapper).insert(any(Voucher.class));

        assertThatThrownBy(() -> service.addSeckillVoucher(validVoucher()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("优惠券保存失败");

        verify(seckillVoucherService, never()).save(any(SeckillVoucher.class));
        verify(outboxPublisher, never()).publish(any(OutboxPublishCommand.class));
    }

    @Test
    void databaseSaveFailureDoesNotPublish() {
        doThrow(new RuntimeException("db unavailable")).when(service).save(any(Voucher.class));

        assertThatThrownBy(() -> service.addSeckillVoucher(validVoucher()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db unavailable");

        verify(seckillVoucherService, never()).save(any(SeckillVoucher.class));
        verify(outboxPublisher, never()).publish(any(OutboxPublishCommand.class));
    }

    @Test
    void seckillVoucherSaveFalseThrowsWithoutPublish() {
        when(seckillVoucherService.save(any(SeckillVoucher.class))).thenReturn(false);

        assertThatThrownBy(() -> service.addSeckillVoucher(validVoucher()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("秒杀券保存失败");

        verify(outboxPublisher, never()).publish(any(OutboxPublishCommand.class));
    }

    @Test
    void publisherFailurePropagatesForTransactionRollback() {
        doThrow(new IllegalStateException("outbox down"))
                .when(outboxPublisher).publish(any(OutboxPublishCommand.class));

        assertThatThrownBy(() -> service.addSeckillVoucher(validVoucher()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outbox down");
    }

    @Test
    void validationFailuresDoNotSaveOrPublish() {
        Voucher voucher = validVoucher();
        voucher.setStock(null);

        assertThatThrownBy(() -> service.addSeckillVoucher(voucher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");

        verify(service, never()).save(any(Voucher.class));
        verify(seckillVoucherService, never()).save(any(SeckillVoucher.class));
        verify(outboxPublisher, never()).publish(any(OutboxPublishCommand.class));
    }

    @Test
    void missingBeginTimeRejected() {
        Voucher voucher = validVoucher();
        voucher.setBeginTime(null);

        assertThatThrownBy(() -> service.addSeckillVoucher(voucher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        verify(outboxPublisher, never()).publish(any(OutboxPublishCommand.class));
    }

    @Test
    void beginNotBeforeEndRejected() {
        Voucher voucher = validVoucher();
        voucher.setEndTime(voucher.getBeginTime());

        assertThatThrownBy(() -> service.addSeckillVoucher(voucher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("开始时间必须早于结束时间");
        verify(outboxPublisher, never()).publish(any(OutboxPublishCommand.class));
    }

    @Test
    void negativeStockRejected() {
        Voucher voucher = validVoucher();
        voucher.setStock(-1);

        assertThatThrownBy(() -> service.addSeckillVoucher(voucher))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("库存不能小于0");
        verify(outboxPublisher, never()).publish(any(OutboxPublishCommand.class));
    }
}
