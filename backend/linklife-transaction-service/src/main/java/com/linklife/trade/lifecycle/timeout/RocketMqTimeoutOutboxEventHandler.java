package com.linklife.trade.lifecycle.timeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.trade.dto.OrderPaymentTimeoutEventPayload;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.lifecycle.outbox.OutboxBusinessHandler;
import com.linklife.trade.lifecycle.outbox.OutboxEventStatus;
import com.linklife.trade.lifecycle.outbox.OutboxHandleResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.SendReceipt;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/** 将可靠 timeout intent 发布为 RocketMQ 5.x 绝对定时消息。 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "linklife.trade.order-timeout.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqTimeoutOutboxEventHandler implements OutboxBusinessHandler {

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private RocketMqOrderTimeoutClientManager rocketMqTimeoutClientManager;

    @Resource
    private ClientServiceProvider rocketMqClientServiceProvider;

    @Resource
    private OrderTimeoutRocketMqProperties rocketMqProperties;

    @Override
    public String eventType() {
        return OrderPaymentTimeoutEvent.EVENT_TYPE;
    }

    @Override
    public int eventVersion() {
        return OrderPaymentTimeoutEvent.EVENT_VERSION;
    }

    @Override
    public OutboxHandleResult handle(OutboxEvent event) {
        String outerError = validateOuter(event);
        if (outerError != null) {
            return OutboxHandleResult.fatal(outerError);
        }
        OrderPaymentTimeoutEventPayload payload;
        try {
            payload = objectMapper.readValue(event.getPayload(), OrderPaymentTimeoutEventPayload.class);
        } catch (Exception e) {
            return OutboxHandleResult.fatal("TIMEOUT_PAYLOAD_INVALID");
        }
        String payloadError = validatePayload(event, payload);
        if (payloadError != null) {
            return OutboxHandleResult.fatal(payloadError);
        }

        long deliveryTimestamp = payload.dueAt().toEpochMilli();
        Message message;
        try {
            message = rocketMqClientServiceProvider.newMessageBuilder()
                    .setTopic(rocketMqProperties.getTopic())
                    .setTag(rocketMqProperties.getTag())
                    .setKeys(event.getEventId(), event.getBusinessKey())
                    .setBody(event.getPayload().getBytes(StandardCharsets.UTF_8))
                    .setDeliveryTimestamp(deliveryTimestamp)
                    .build();
        } catch (IllegalArgumentException e) {
            return OutboxHandleResult.fatal("ROCKETMQ_MESSAGE_INVALID");
        }

        try {
            Instant sendAt = Instant.now();
            SendReceipt receipt = rocketMqTimeoutClientManager.send(message);
            if (receipt == null || receipt.getMessageId() == null) {
                return OutboxHandleResult.retryable("ROCKETMQ_SEND_RESULT_UNKNOWN");
            }
            log.info("RocketMQ timeout 已确认发送 orderId={} eventId={} messageId={} sendAt={} dueAt={}",
                    payload.orderId(), payload.eventId(), receipt.getMessageId(), sendAt, payload.dueAt());
            return OutboxHandleResult.success();
        } catch (ClientException | RuntimeException e) {
            log.warn("RocketMQ timeout 发送失败，保留 Outbox 重试 orderId={} eventId={} errorType={}",
                    payload.orderId(), payload.eventId(), e.getClass().getSimpleName());
            return OutboxHandleResult.retryable("ROCKETMQ_SEND_FAILED");
        }
    }

    private String validateOuter(OutboxEvent event) {
        if (event == null) return "TIMEOUT_OUTBOX_INVALID";
        if (!OutboxEventStatus.PROCESSING.name().equals(event.getStatus())) return "TIMEOUT_OUTBOX_STATUS_INVALID";
        if (event.getLockToken() == null || event.getLockToken().isBlank()) return "TIMEOUT_OUTBOX_LOCK_MISSING";
        if (!OrderPaymentTimeoutEvent.AGGREGATE_TYPE.equals(event.getAggregateType())) return "TIMEOUT_AGGREGATE_INVALID";
        if (!OrderPaymentTimeoutEvent.EVENT_TYPE.equals(event.getEventType())) return "TIMEOUT_EVENT_TYPE_INVALID";
        if (event.getEventVersion() == null
                || event.getEventVersion() != OrderPaymentTimeoutEvent.EVENT_VERSION) return "TIMEOUT_EVENT_VERSION_INVALID";
        if (event.getAggregateId() == null || event.getAggregateId() <= 0) return "TIMEOUT_AGGREGATE_ID_INVALID";
        if (event.getEventId() == null || event.getEventId().isBlank()) return "TIMEOUT_EVENT_ID_MISSING";
        if (!OrderPaymentTimeoutEvent.businessKey(event.getAggregateId()).equals(event.getBusinessKey())) {
            return "TIMEOUT_BUSINESS_KEY_INVALID";
        }
        if (event.getPayload() == null || event.getPayload().isBlank()) return "TIMEOUT_PAYLOAD_MISSING";
        return null;
    }

    private String validatePayload(OutboxEvent event, OrderPaymentTimeoutEventPayload payload) {
        if (payload == null) return "TIMEOUT_PAYLOAD_INVALID";
        if (!event.getEventId().equals(payload.eventId())) return "TIMEOUT_EVENT_ID_MISMATCH";
        if (payload.eventVersion() != OrderPaymentTimeoutEvent.EVENT_VERSION) return "TIMEOUT_VERSION_MISMATCH";
        if (payload.orderId() != event.getAggregateId()) return "TIMEOUT_ORDER_ID_MISMATCH";
        if (payload.userId() <= 0 || payload.voucherId() <= 0) return "TIMEOUT_IDENTITY_INVALID";
        if (payload.createdAt() == null || payload.createdAtInstant() == null
                || payload.dueAt() == null
                || !payload.dueAt().isAfter(payload.createdAtInstant())) {
            return "TIMEOUT_DUE_AT_INVALID";
        }
        return null;
    }
}
