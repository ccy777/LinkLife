package com.linklife.trade.lifecycle.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.linklife.trade.dto.OrderClosedEventPayload;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderClosedOutboxEventHandler 单元测试：外层契约、payload 身份、MySQL 事实校验、
 * Redis 结果映射、验证失败不触碰 Redis、Handler 不直接更新 Outbox、不执行 SREM。
 */
class OrderClosedOutboxEventHandlerTest {

    private static final LocalDateTime CLOSED_AT = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

    private OrderClosedOutboxEventHandler handler;
    private VoucherOrderMapper orderMapper;
    private RedisOrderCloseCompensationAdapter compensationAdapter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        handler = new OrderClosedOutboxEventHandler();
        orderMapper = mock(VoucherOrderMapper.class);
        compensationAdapter = mock(RedisOrderCloseCompensationAdapter.class);
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        ReflectionTestUtils.setField(handler, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(handler, "voucherOrderMapper", orderMapper);
        ReflectionTestUtils.setField(handler, "compensationAdapter", compensationAdapter);
    }

    private OutboxEvent validEvent(String triggerType) {
        OutboxEvent event = new OutboxEvent();
        event.setId(1L);
        event.setEventId("event-1");
        event.setBusinessKey("VOUCHER_ORDER:CLOSED:1001:V1");
        event.setAggregateType("VOUCHER_ORDER");
        event.setAggregateId(1001L);
        event.setEventType("ORDER_CLOSED");
        event.setEventVersion(1);
        event.setStatus("PROCESSING");
        event.setLockToken("token-1");
        event.setPayload(payloadJson("event-1", 1, 1001L, 1L, 2L,
                VoucherOrderStatus.CANCELED.getCode(), triggerType, CLOSED_AT));
        return event;
    }

    private String payloadJson(String eventId, int eventVersion, long orderId, long userId,
                               long voucherId, int toStatus, String triggerType, LocalDateTime closedAt) {
        try {
            OrderClosedEventPayload payload = new OrderClosedEventPayload(
                    eventId, eventVersion, orderId, userId, voucherId, toStatus, triggerType, closedAt);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("payload 构造失败", e);
        }
    }

    private VoucherOrder canceledOrder(long userId, long voucherId) {
        VoucherOrder order = new VoucherOrder();
        order.setId(1001L);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(VoucherOrderStatus.CANCELED.getCode());
        return order;
    }

    private void stubSuccessCompensation() {
        when(compensationAdapter.compensate(any(OrderCloseCompensationCommand.class)))
                .thenReturn(OrderCloseCompensationResult.success());
    }

    @Test
    void userCancelEventSuccess() {
        when(orderMapper.selectOne(any())).thenReturn(canceledOrder(1L, 2L));
        stubSuccessCompensation();

        OutboxHandleResult result = handler.handle(validEvent("USER_CANCEL"));

        assertThat(result).isEqualTo(OutboxHandleResult.success());
        verify(compensationAdapter).compensate(any(OrderCloseCompensationCommand.class));
    }

    @Test
    void timeoutCloseEventSuccess() {
        when(orderMapper.selectOne(any())).thenReturn(canceledOrder(1L, 2L));
        stubSuccessCompensation();

        assertThat(handler.handle(validEvent("TIMEOUT_CLOSE")))
                .isEqualTo(OutboxHandleResult.success());
    }

    @Test
    void redisRetryableAndFatalMapThrough() {
        when(orderMapper.selectOne(any())).thenReturn(canceledOrder(1L, 2L));
        when(compensationAdapter.compensate(any(OrderCloseCompensationCommand.class)))
                .thenReturn(OrderCloseCompensationResult.retryable("REDIS_STOCK_INCREMENT_FAILED"));

        assertThat(handler.handle(validEvent("USER_CANCEL")))
                .isEqualTo(OutboxHandleResult.retryable("REDIS_STOCK_INCREMENT_FAILED"));

        when(compensationAdapter.compensate(any(OrderCloseCompensationCommand.class)))
                .thenReturn(OrderCloseCompensationResult.fatal("REDIS_MARKER_CORRUPT"));
        assertThat(handler.handle(validEvent("USER_CANCEL")))
                .isEqualTo(OutboxHandleResult.fatal("REDIS_MARKER_CORRUPT"));
    }

    @Test
    void outerStatusInvalid() {
        OutboxEvent event = validEvent("USER_CANCEL");
        event.setStatus("PENDING");

        assertFatal(event, "OUTBOX_STATUS_INVALID");
    }

    @Test
    void lockTokenMissingRejected() {
        OutboxEvent event = validEvent("USER_CANCEL");
        event.setLockToken(null);

        assertFatal(event, "OUTBOX_LOCK_TOKEN_MISSING");
    }

    @Test
    void aggregateAndEventContractInvalid() {
        OutboxEvent wrongAggregate = validEvent("USER_CANCEL");
        wrongAggregate.setAggregateType("OTHER");
        assertFatal(wrongAggregate, "OUTBOX_AGGREGATE_TYPE_INVALID");

        OutboxEvent wrongType = validEvent("USER_CANCEL");
        wrongType.setEventType("ORDER_PAID");
        assertFatal(wrongType, "OUTBOX_EVENT_TYPE_INVALID");

        OutboxEvent wrongVersion = validEvent("USER_CANCEL");
        wrongVersion.setEventVersion(2);
        assertFatal(wrongVersion, "OUTBOX_EVENT_VERSION_INVALID");

        OutboxEvent zeroAggregate = validEvent("USER_CANCEL");
        zeroAggregate.setAggregateId(0L);
        assertFatal(zeroAggregate, "OUTBOX_AGGREGATE_ID_INVALID");
    }

    @Test
    void businessKeyInvalid() {
        OutboxEvent event = validEvent("USER_CANCEL");
        event.setBusinessKey("WRONG");

        assertFatal(event, "OUTBOX_BUSINESS_KEY_INVALID");
    }

    @Test
    void corruptPayloadJson() {
        OutboxEvent event = validEvent("USER_CANCEL");
        event.setPayload("{not-json");

        assertFatal(event, "PAYLOAD_INVALID");
    }

    @Test
    void payloadIdentityMismatch() {
        OutboxEvent event = validEvent("USER_CANCEL");
        event.setPayload(payloadJson("other-event", 1, 1001L, 1L, 2L,
                VoucherOrderStatus.CANCELED.getCode(), "USER_CANCEL", CLOSED_AT));
        assertFatal(event, "PAYLOAD_EVENT_ID_MISMATCH");

        OutboxEvent orderMismatch = validEvent("USER_CANCEL");
        orderMismatch.setPayload(payloadJson("event-1", 1, 2002L, 1L, 2L,
                VoucherOrderStatus.CANCELED.getCode(), "USER_CANCEL", CLOSED_AT));
        assertFatal(orderMismatch, "PAYLOAD_ORDER_ID_MISMATCH");
    }

    @Test
    void payloadBusinessFactsInvalid() {
        OutboxEvent event = validEvent("USER_CANCEL");
        event.setPayload(payloadJson("event-1", 1, 1001L, 0L, 2L,
                VoucherOrderStatus.CANCELED.getCode(), "USER_CANCEL", CLOSED_AT));
        assertFatal(event, "PAYLOAD_USER_ID_INVALID");

        OutboxEvent noVoucher = validEvent("USER_CANCEL");
        noVoucher.setPayload(payloadJson("event-1", 1, 1001L, 1L, 0L,
                VoucherOrderStatus.CANCELED.getCode(), "USER_CANCEL", CLOSED_AT));
        assertFatal(noVoucher, "PAYLOAD_VOUCHER_ID_INVALID");

        OutboxEvent wrongStatus = validEvent("USER_CANCEL");
        wrongStatus.setPayload(payloadJson("event-1", 1, 1001L, 1L, 2L,
                VoucherOrderStatus.PAID.getCode(), "USER_CANCEL", CLOSED_AT));
        assertFatal(wrongStatus, "PAYLOAD_TO_STATUS_INVALID");

        OutboxEvent wrongTrigger = validEvent("USER_CANCEL");
        wrongTrigger.setPayload(payloadJson("event-1", 1, 1001L, 1L, 2L,
                VoucherOrderStatus.CANCELED.getCode(), "REFUND", CLOSED_AT));
        assertFatal(wrongTrigger, "PAYLOAD_TRIGGER_TYPE_INVALID");

        OutboxEvent nullClosedAt = validEvent("USER_CANCEL");
        nullClosedAt.setPayload(payloadJson("event-1", 1, 1001L, 1L, 2L,
                VoucherOrderStatus.CANCELED.getCode(), "USER_CANCEL", null));
        assertFatal(nullClosedAt, "PAYLOAD_CLOSED_AT_MISSING");
    }

    @Test
    void orderNotFoundIsFatal() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertFatal(validEvent("USER_CANCEL"), "ORDER_NOT_FOUND");
    }

    @Test
    void orderStateMismatchIsFatal() {
        VoucherOrder paid = canceledOrder(1L, 2L);
        paid.setStatus(VoucherOrderStatus.PAID.getCode());
        when(orderMapper.selectOne(any())).thenReturn(paid);

        assertFatal(validEvent("USER_CANCEL"), "ORDER_STATE_MISMATCH");
    }

    @Test
    void orderIdentityMismatchIsFatal() {
        when(orderMapper.selectOne(any())).thenReturn(canceledOrder(9L, 2L));

        assertFatal(validEvent("USER_CANCEL"), "ORDER_IDENTITY_MISMATCH");
    }

    @Test
    void dbReadFailureIsRetryable() {
        when(orderMapper.selectOne(any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThat(handler.handle(validEvent("USER_CANCEL")))
                .isEqualTo(OutboxHandleResult.retryable("ORDER_READ_FAILED"));
    }

    @Test
    void handledAtUsesClockNotPayloadClosedAt() {
        // payload.closedAt = T0（validEvent 默认 2026-08-06T10:00:00）
        LocalDateTime executionTime = LocalDateTime.of(2026, 8, 6, 11, 30, 45);
        ReflectionTestUtils.setField(handler, "clock",
                Clock.fixed(executionTime.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        when(orderMapper.selectOne(any())).thenReturn(canceledOrder(1L, 2L));
        stubSuccessCompensation();

        handler.handle(validEvent("USER_CANCEL"));

        ArgumentCaptor<OrderCloseCompensationCommand> captor =
                ArgumentCaptor.forClass(OrderCloseCompensationCommand.class);
        verify(compensationAdapter).compensate(captor.capture());
        OrderCloseCompensationCommand command = captor.getValue();
        // handledAt = 本次 Redis 补偿实际执行时间 T1，而不是 payload.closedAt
        assertThat(command.handledAt()).isEqualTo(executionTime);
        assertThat(command.handledAt()).isNotEqualTo(CLOSED_AT);
    }

    @Test
    void handledAtNanosTruncatedToSeconds() {
        LocalDateTime executionTime = LocalDateTime.of(2026, 8, 6, 11, 30, 45, 123_000_000);
        ReflectionTestUtils.setField(handler, "clock",
                Clock.fixed(executionTime.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
        when(orderMapper.selectOne(any())).thenReturn(canceledOrder(1L, 2L));
        stubSuccessCompensation();

        handler.handle(validEvent("USER_CANCEL"));

        ArgumentCaptor<OrderCloseCompensationCommand> captor =
                ArgumentCaptor.forClass(OrderCloseCompensationCommand.class);
        verify(compensationAdapter).compensate(captor.capture());
        assertThat(captor.getValue().handledAt())
                .isEqualTo(executionTime.truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void validationFailuresNeverTouchRedis() {
        OutboxEvent badStatus = validEvent("USER_CANCEL");
        badStatus.setStatus("PENDING");
        handler.handle(badStatus);

        OutboxEvent badPayload = validEvent("USER_CANCEL");
        badPayload.setPayload("{bad");
        handler.handle(badPayload);

        OutboxEvent badOrder = validEvent("USER_CANCEL");
        when(orderMapper.selectOne(any())).thenReturn(null);
        handler.handle(badOrder);

        verify(compensationAdapter, never()).compensate(any(OrderCloseCompensationCommand.class));
    }

    @Test
    void handlerDoesNotUpdateOutboxAndDoesNotSrem() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/lifecycle/outbox/OrderClosedOutboxEventHandler.java")),
                StandardCharsets.UTF_8);
        assertThat(source)
                .doesNotContain("OutboxEventMapper")
                .doesNotContain("outboxEventMapper")
                .doesNotContain("import com.linklife.trade.mapper.OutboxEventMapper");
    }

    private void assertFatal(OutboxEvent event, String expectedCode) {
        OutboxHandleResult result = handler.handle(event);
        assertThat(result.type()).isEqualTo(OutboxHandleResult.OutboxHandleResultType.FATAL_FAILURE);
        assertThat(result.errorCode()).isEqualTo(expectedCode);
        verify(compensationAdapter, never()).compensate(any(OrderCloseCompensationCommand.class));
    }
}
