package com.linklife.trade.lifecycle.timeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.linklife.trade.dto.OrderPaymentTimeoutEventPayload;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RocketMqOrderTimeoutMessageListenerTest {

    private RocketMqOrderTimeoutMessageListener listener;
    private ObjectMapper objectMapper;
    private OrderPaymentTimeoutMessageProcessor processor;
    private OrderTimeoutRocketMqProperties properties;

    @BeforeEach
    void setUp() {
        listener = new RocketMqOrderTimeoutMessageListener();
        objectMapper = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        processor = mock(OrderPaymentTimeoutMessageProcessor.class);
        properties = new OrderTimeoutRocketMqProperties();
        properties.setTopic("timeout-topic");
        properties.setTag("TIMEOUT");
        ReflectionTestUtils.setField(listener, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(listener, "rocketMqProperties", properties);
        ReflectionTestUtils.setField(listener, "processor", processor);
    }

    @Test
    void validMessageAcknowledgesOnlySafeBusinessOutcomes() throws Exception {
        when(processor.process(any())).thenReturn(
                OrderPaymentTimeoutMessageProcessor.ProcessResult.CLOSED);
        assertThat(listener.consume(message(payload(1)))).isEqualTo(ConsumeResult.SUCCESS);

        when(processor.process(any())).thenReturn(
                OrderPaymentTimeoutMessageProcessor.ProcessResult.TOO_EARLY);
        assertThat(listener.consume(message(payload(1)))).isEqualTo(ConsumeResult.FAILURE);

        when(processor.process(any())).thenReturn(
                OrderPaymentTimeoutMessageProcessor.ProcessResult.IDENTITY_MISMATCH);
        assertThat(listener.consume(message(payload(1)))).isEqualTo(ConsumeResult.SUCCESS);
    }

    @Test
    void unknownVersionAndWrongKeysArePoisonAndNeverReachBusinessProcessor() throws Exception {
        assertThat(listener.consume(message(payload(2)))).isEqualTo(ConsumeResult.SUCCESS);

        MessageView wrongKeys = message(payload(1));
        when(wrongKeys.getKeys()).thenReturn(List.of("wrong"));
        assertThat(listener.consume(wrongKeys)).isEqualTo(ConsumeResult.SUCCESS);
        verify(processor, never()).process(any());
    }

    @Test
    void malformedBodyIsPoisonButDatabaseFailureRequestsRetry() throws Exception {
        MessageView malformed = baseView();
        when(malformed.getBody()).thenReturn(ByteBuffer.wrap("not-json".getBytes()));
        assertThat(listener.consume(malformed)).isEqualTo(ConsumeResult.SUCCESS);

        MessageView missingBody = baseView();
        when(missingBody.getBody()).thenReturn(null);
        assertThat(listener.consume(missingBody)).isEqualTo(ConsumeResult.SUCCESS);

        when(processor.process(any())).thenThrow(new RuntimeException("db down"));
        assertThat(listener.consume(message(payload(1)))).isEqualTo(ConsumeResult.FAILURE);
    }

    private OrderPaymentTimeoutEventPayload payload(int version) {
        LocalDateTime created = LocalDateTime.of(2026, 8, 18, 10, 0);
        return new OrderPaymentTimeoutEventPayload(
                "event-1", version, 1001L, 11L, 22L, created,
                Instant.parse("2026-08-18T02:00:00Z"),
                Instant.parse("2026-08-18T02:15:00Z"));
    }

    private MessageView message(OrderPaymentTimeoutEventPayload payload) throws Exception {
        MessageView view = baseView();
        when(view.getBody()).thenReturn(ByteBuffer.wrap(objectMapper.writeValueAsBytes(payload)));
        when(view.getKeys()).thenReturn(List.of(
                payload.eventId(), OrderPaymentTimeoutEvent.businessKey(payload.orderId())));
        return view;
    }

    private MessageView baseView() {
        MessageView view = mock(MessageView.class);
        when(view.getTopic()).thenReturn("timeout-topic");
        when(view.getTag()).thenReturn(Optional.of("TIMEOUT"));
        MessageId id = mock(MessageId.class);
        when(id.toString()).thenReturn("message-id");
        when(view.getMessageId()).thenReturn(id);
        return view;
    }
}
