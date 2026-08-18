package com.linklife.trade.lifecycle.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linklife.promotion.entity.SeckillVoucher;
import com.linklife.promotion.service.ISeckillVoucherService;
import com.linklife.shared.event.SeckillVoucherCreatedEventPayload;
import com.linklife.trade.entity.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SeckillVoucherCreatedOutboxEventHandler 单元测试：外层/payload/MySQL 校验、
 * currentStock&lt;initialStock 合法、校验失败不调用 Redis、结果映射、不更新 Outbox。
 */
class SeckillVoucherCreatedOutboxEventHandlerTest {

    private static final long BEGIN_MILLIS = 1750000000000L;
    private static final long END_MILLIS = 1751000000000L;

    private SeckillVoucherCreatedOutboxEventHandler handler;
    private ISeckillVoucherService seckillVoucherService;
    private SeckillVoucherInitializeAdapter initializeAdapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        handler = new SeckillVoucherCreatedOutboxEventHandler();
        seckillVoucherService = mock(ISeckillVoucherService.class);
        initializeAdapter = mock(SeckillVoucherInitializeAdapter.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        ReflectionTestUtils.setField(handler, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(handler, "seckillVoucherService", seckillVoucherService);
        ReflectionTestUtils.setField(handler, "initializeAdapter", initializeAdapter);
    }

    private OutboxEvent validEvent() {
        OutboxEvent event = new OutboxEvent();
        event.setStatus("PROCESSING");
        event.setLockToken("token-1");
        event.setAggregateType("SECKILL_VOUCHER");
        event.setAggregateId(100L);
        event.setEventType("SECKILL_VOUCHER_CREATED");
        event.setEventVersion(1);
        event.setEventId("event-1");
        event.setBusinessKey("SECKILL_VOUCHER:CREATED:100:V1");
        event.setPayload(payloadJson("event-1", 1, 100L, 10, BEGIN_MILLIS, END_MILLIS,
                LocalDateTime.of(2026, 8, 6, 10, 0, 0)));
        return event;
    }

    private String payloadJson(String eventId, int version, long voucherId, int stock,
                               long begin, long end, LocalDateTime createdAt) {
        try {
            return objectMapper.writeValueAsString(new SeckillVoucherCreatedEventPayload(
                    eventId, version, voucherId, stock, begin, end, createdAt));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SeckillVoucher seckillVoucher(int currentStock) {
        SeckillVoucher voucher = new SeckillVoucher();
        voucher.setVoucherId(100L);
        voucher.setStock(currentStock);
        voucher.setBeginTime(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(BEGIN_MILLIS), java.time.ZoneId.systemDefault()));
        voucher.setEndTime(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(END_MILLIS), java.time.ZoneId.systemDefault()));
        return voucher;
    }

    private void stubSuccessInit() {
        when(initializeAdapter.initialize(any(SeckillVoucherInitializeCommand.class)))
                .thenReturn(SeckillVoucherInitializeResult.success());
    }

    @Test
    void validEventInitializesRedisAndSucceeds() {
        when(seckillVoucherService.getById(100L)).thenReturn(seckillVoucher(10));
        stubSuccessInit();

        assertThat(handler.handle(validEvent())).isEqualTo(OutboxHandleResult.success());
        verify(initializeAdapter).initialize(any(SeckillVoucherInitializeCommand.class));
    }

    @Test
    void currentStockBelowInitialStockIsAllowed() {
        when(seckillVoucherService.getById(100L)).thenReturn(seckillVoucher(3));
        stubSuccessInit();

        assertThat(handler.handle(validEvent())).isEqualTo(OutboxHandleResult.success());
    }

    @Test
    void outerValidationFailuresAreFatalWithoutRedis() {
        OutboxEvent event = validEvent();
        event.setStatus("PENDING");
        assertFatal(event, "OUTBOX_STATUS_INVALID");

        event = validEvent();
        event.setLockToken(null);
        assertFatal(event, "OUTBOX_LOCK_TOKEN_MISSING");

        event = validEvent();
        event.setAggregateType("OTHER");
        assertFatal(event, "OUTBOX_AGGREGATE_TYPE_INVALID");

        event = validEvent();
        event.setEventType("OTHER");
        assertFatal(event, "OUTBOX_EVENT_TYPE_INVALID");

        event = validEvent();
        event.setBusinessKey("WRONG");
        assertFatal(event, "OUTBOX_BUSINESS_KEY_INVALID");
    }

    @Test
    void payloadValidationFailuresAreFatalWithoutRedis() {
        OutboxEvent event = validEvent();
        event.setPayload("{bad");
        assertFatal(event, "PAYLOAD_INVALID");

        event = validEvent();
        event.setPayload(payloadJson("other", 1, 100L, 10, BEGIN_MILLIS, END_MILLIS,
                LocalDateTime.of(2026, 8, 6, 10, 0, 0)));
        assertFatal(event, "PAYLOAD_EVENT_ID_MISMATCH");

        event = validEvent();
        event.setPayload(payloadJson("event-1", 1, 101L, 10, BEGIN_MILLIS, END_MILLIS,
                LocalDateTime.of(2026, 8, 6, 10, 0, 0)));
        assertFatal(event, "PAYLOAD_VOUCHER_ID_MISMATCH");

        event = validEvent();
        event.setPayload(payloadJson("event-1", 1, 100L, -1, BEGIN_MILLIS, END_MILLIS,
                LocalDateTime.of(2026, 8, 6, 10, 0, 0)));
        assertFatal(event, "PAYLOAD_INITIAL_STOCK_INVALID");

        event = validEvent();
        event.setPayload(payloadJson("event-1", 1, 100L, 10, END_MILLIS, BEGIN_MILLIS,
                LocalDateTime.of(2026, 8, 6, 10, 0, 0)));
        assertFatal(event, "PAYLOAD_BEGIN_END_INVALID");
    }

    @Test
    void mySqlFactFailuresAreFatalWithoutRedis() {
        when(seckillVoucherService.getById(100L)).thenReturn(null);
        assertFatal(validEvent(), "SECKILL_VOUCHER_NOT_FOUND");

        SeckillVoucher wrongId = seckillVoucher(10);
        wrongId.setVoucherId(999L);
        when(seckillVoucherService.getById(100L)).thenReturn(wrongId);
        assertFatal(validEvent(), "SECKILL_IDENTITY_MISMATCH");

        SeckillVoucher overStock = seckillVoucher(11);
        when(seckillVoucherService.getById(100L)).thenReturn(overStock);
        assertFatal(validEvent(), "SECKILL_STOCK_INVALID");

        SeckillVoucher wrongTime = seckillVoucher(10);
        wrongTime.setBeginTime(wrongTime.getBeginTime().plusSeconds(1));
        when(seckillVoucherService.getById(100L)).thenReturn(wrongTime);
        assertFatal(validEvent(), "SECKILL_TIME_MISMATCH");
    }

    @Test
    void dbReadFailureIsRetryableWithoutRedis() {
        when(seckillVoucherService.getById(100L))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(handler.handle(validEvent()))
                .isEqualTo(OutboxHandleResult.retryable("SECKILL_READ_FAILED"));
        verify(initializeAdapter, never()).initialize(any(SeckillVoucherInitializeCommand.class));
    }

    @Test
    void redisResultMapsThrough() {
        when(seckillVoucherService.getById(100L)).thenReturn(seckillVoucher(10));
        when(initializeAdapter.initialize(any(SeckillVoucherInitializeCommand.class)))
                .thenReturn(SeckillVoucherInitializeResult.retryable("SECKILL_INIT_WRITE_ROLLED_BACK"));
        assertThat(handler.handle(validEvent()))
                .isEqualTo(OutboxHandleResult.retryable("SECKILL_INIT_WRITE_ROLLED_BACK"));

        when(initializeAdapter.initialize(any(SeckillVoucherInitializeCommand.class)))
                .thenReturn(SeckillVoucherInitializeResult.fatal("SECKILL_INIT_PREEXISTING_STATE_CONFLICT"));
        assertThat(handler.handle(validEvent()))
                .isEqualTo(OutboxHandleResult.fatal("SECKILL_INIT_PREEXISTING_STATE_CONFLICT"));
    }

    private void assertFatal(OutboxEvent event, String expectedCode) {
        OutboxHandleResult result = handler.handle(event);
        assertThat(result.type()).isEqualTo(OutboxHandleResult.OutboxHandleResultType.FATAL_FAILURE);
        assertThat(result.errorCode()).isEqualTo(expectedCode);
        verify(initializeAdapter, never()).initialize(any(SeckillVoucherInitializeCommand.class));
    }
}
