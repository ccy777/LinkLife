package com.linklife.trade.lifecycle.timeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.linklife.trade.dto.OrderPaymentTimeoutEventPayload;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.lifecycle.outbox.OutboxHandleResult;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RocketMqTimeoutOutboxEventHandlerTest {

    private RocketMqTimeoutOutboxEventHandler handler;
    private ObjectMapper objectMapper;
    private RocketMqOrderTimeoutClientManager clientManager;
    private MessageBuilder builder;
    private Message message;

    @BeforeEach
    void setUp() {
        handler = new RocketMqTimeoutOutboxEventHandler();
        objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        clientManager = mock(RocketMqOrderTimeoutClientManager.class);
        ClientServiceProvider provider = mock(ClientServiceProvider.class);
        builder = mock(MessageBuilder.class);
        message = mock(Message.class);
        when(provider.newMessageBuilder()).thenReturn(builder);
        when(builder.setTopic(anyString())).thenReturn(builder);
        when(builder.setTag(anyString())).thenReturn(builder);
        when(builder.setKeys(any(String[].class))).thenReturn(builder);
        when(builder.setBody(any(byte[].class))).thenReturn(builder);
        when(builder.setDeliveryTimestamp(anyLong())).thenReturn(builder);
        when(builder.build()).thenReturn(message);

        OrderTimeoutRocketMqProperties mq = new OrderTimeoutRocketMqProperties();
        mq.setEnabled(true);
        mq.setEndpoints("127.0.0.1:8081");
        ReflectionTestUtils.setField(handler, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(handler, "rocketMqTimeoutClientManager", clientManager);
        ReflectionTestUtils.setField(handler, "rocketMqClientServiceProvider", provider);
        ReflectionTestUtils.setField(handler, "rocketMqProperties", mq);
    }

    @Test
    void sendsExactDelayMessageAndOnlyReceiptMeansSuccess() throws Exception {
        SendReceipt receipt = mock(SendReceipt.class);
        when(receipt.getMessageId()).thenReturn(mock(MessageId.class));
        when(clientManager.send(message)).thenReturn(receipt);
        OutboxEvent event = validEvent();

        assertThat(handler.handle(event)).isEqualTo(OutboxHandleResult.success());

        verify(builder).setTopic("linklife-order-payment-timeout");
        verify(builder).setTag("PAYMENT_TIMEOUT_CHECK");
        verify(builder).setKeys(event.getEventId(), event.getBusinessKey());
        verify(builder).setDeliveryTimestamp(1787019300000L);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(builder).setBody(body.capture());
        assertThat(new String(body.getValue(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(event.getPayload());
    }

    @Test
    void clientFailureAndUnknownReceiptAreRetryable() throws Exception {
        when(clientManager.send(message)).thenThrow(new ClientException("broker down"));
        assertThat(handler.handle(validEvent()).type())
                .isEqualTo(OutboxHandleResult.OutboxHandleResultType.RETRYABLE_FAILURE);

        org.mockito.Mockito.doThrow(new IllegalStateException("gRPC stream closed"))
                .when(clientManager).send(message);
        assertThat(handler.handle(validEvent()).errorCode()).isEqualTo("ROCKETMQ_SEND_FAILED");

        org.mockito.Mockito.doReturn(null).when(clientManager).send(message);
        assertThat(handler.handle(validEvent()).errorCode())
                .isEqualTo("ROCKETMQ_SEND_RESULT_UNKNOWN");
    }

    @Test
    void malformedOrIdentityMismatchedPayloadIsFatalWithoutSend() throws Exception {
        OutboxEvent event = validEvent();
        event.setPayload("not-json");
        assertThat(handler.handle(event).type())
                .isEqualTo(OutboxHandleResult.OutboxHandleResultType.FATAL_FAILURE);

        event = validEvent();
        event.setBusinessKey("wrong");
        assertThat(handler.handle(event).errorCode()).isEqualTo("TIMEOUT_BUSINESS_KEY_INVALID");
        verify(clientManager, never()).send(any(Message.class));
    }

    private OutboxEvent validEvent() throws Exception {
        String eventId = "4e0c49a1-855e-4c9c-b50c-0f2fa593e2d1";
        long orderId = 1001L;
        LocalDateTime createdAt = LocalDateTime.of(2026, 8, 18, 10, 0);
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setBusinessKey(OrderPaymentTimeoutEvent.businessKey(orderId));
        event.setAggregateType(OrderPaymentTimeoutEvent.AGGREGATE_TYPE);
        event.setAggregateId(orderId);
        event.setEventType(OrderPaymentTimeoutEvent.EVENT_TYPE);
        event.setEventVersion(1);
        event.setStatus("PROCESSING");
        event.setLockToken("lock-token");
        event.setPayload(objectMapper.writeValueAsString(new OrderPaymentTimeoutEventPayload(
                eventId, 1, orderId, 11L, 22L, createdAt,
                createdAt.atZone(ZoneId.of("Asia/Shanghai")).toInstant(),
                createdAt.plusMinutes(15).atZone(ZoneId.of("Asia/Shanghai")).toInstant())));
        return event;
    }
}
