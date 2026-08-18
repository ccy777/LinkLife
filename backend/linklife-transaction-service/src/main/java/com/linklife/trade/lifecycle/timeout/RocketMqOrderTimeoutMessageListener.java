package com.linklife.trade.lifecycle.timeout;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.trade.dto.OrderPaymentTimeoutEventPayload;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collection;

/** RocketMQ PushConsumer listener：契约错误隔离，事实不确定时请求重试。 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "linklife.trade.order-timeout.rocketmq", name = "enabled", havingValue = "true")
public class RocketMqOrderTimeoutMessageListener implements MessageListener {

    private static final int MAX_BODY_BYTES = 4096;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private OrderTimeoutRocketMqProperties rocketMqProperties;

    @Resource
    private OrderPaymentTimeoutMessageProcessor processor;

    @Override
    public ConsumeResult consume(MessageView messageView) {
        OrderPaymentTimeoutEventPayload payload;
        try {
            payload = parseAndValidate(messageView);
        } catch (PoisonMessageException e) {
            log.warn("RocketMQ timeout poison message 已隔离 reason={} messageId={}",
                    e.reason, safeMessageId(messageView));
            return ConsumeResult.SUCCESS;
        }

        try {
            Instant consumeAt = Instant.now();
            OrderPaymentTimeoutMessageProcessor.ProcessResult result = processor.process(payload);
            log.info("RocketMQ timeout 已消费 orderId={} eventId={} result={} dueAt={} consumeAt={}",
                    payload.orderId(), payload.eventId(), result, payload.dueAt(), consumeAt);
            return switch (result) {
                case CLOSED, ALREADY_CANCELED, NOT_CLOSABLE -> ConsumeResult.SUCCESS;
                case IDENTITY_MISMATCH, INVALID_ORDER_FACT -> {
                    log.warn("RocketMQ timeout 消息与 MySQL 事实不一致，已 fail-closed result={} orderId={}",
                            result, payload.orderId());
                    yield ConsumeResult.SUCCESS;
                }
                case NOT_FOUND, TOO_EARLY -> ConsumeResult.FAILURE;
            };
        } catch (RuntimeException e) {
            log.warn("RocketMQ timeout 处理失败，交由 Broker 重试 orderId={} errorType={}",
                    payload.orderId(), e.getClass().getSimpleName());
            return ConsumeResult.FAILURE;
        }
    }

    private OrderPaymentTimeoutEventPayload parseAndValidate(MessageView view) {
        if (view == null) throw poison("MESSAGE_NULL");
        if (!rocketMqProperties.getTopic().equals(view.getTopic())) throw poison("TOPIC_MISMATCH");
        if (view.getTag().isEmpty() || !rocketMqProperties.getTag().equals(view.getTag().get())) {
            throw poison("TAG_MISMATCH");
        }
        if (view.getBody() == null) throw poison("BODY_MISSING");
        ByteBuffer buffer = view.getBody().asReadOnlyBuffer();
        if (!buffer.hasRemaining() || buffer.remaining() > MAX_BODY_BYTES) throw poison("BODY_SIZE_INVALID");
        byte[] body = new byte[buffer.remaining()];
        buffer.get(body);

        OrderPaymentTimeoutEventPayload payload;
        try {
            payload = objectMapper.readValue(new String(body, StandardCharsets.UTF_8),
                    OrderPaymentTimeoutEventPayload.class);
        } catch (Exception e) {
            throw poison("PAYLOAD_INVALID");
        }
        if (payload.eventId() == null || payload.eventId().isBlank()) throw poison("EVENT_ID_MISSING");
        if (payload.eventVersion() != OrderPaymentTimeoutEvent.EVENT_VERSION) throw poison("VERSION_UNSUPPORTED");
        if (payload.orderId() <= 0 || payload.userId() <= 0 || payload.voucherId() <= 0) {
            throw poison("IDENTITY_INVALID");
        }
        if (payload.createdAt() == null || payload.createdAtInstant() == null
                || payload.dueAt() == null
                || !payload.dueAt().isAfter(payload.createdAtInstant())) throw poison("TIME_INVALID");
        Collection<String> keys = view.getKeys();
        if (keys == null || !keys.contains(payload.eventId())
                || !keys.contains(OrderPaymentTimeoutEvent.businessKey(payload.orderId()))) {
            throw poison("KEYS_MISMATCH");
        }
        return payload;
    }

    private String safeMessageId(MessageView view) {
        return view == null || view.getMessageId() == null ? "unknown" : view.getMessageId().toString();
    }

    private PoisonMessageException poison(String reason) {
        return new PoisonMessageException(reason);
    }

    private static final class PoisonMessageException extends RuntimeException {
        private final String reason;
        private PoisonMessageException(String reason) { this.reason = reason; }
    }
}
