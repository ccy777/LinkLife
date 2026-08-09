package com.linklife.trade.lifecycle.outbox;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.trade.dto.OrderClosedEventPayload;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.lifecycle.close.OrderCloseTriggerType;
import com.linklife.trade.mapper.VoucherOrderMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * ORDER_CLOSED 事件业务 Handler（Stage 3E 017F；017J-B 起由 OutboxEventRouter 精确路由）。
 *
 * <p>只处理 {@code ORDER_CLOSED V1}：先校验 Outbox 外层契约、payload 身份与 MySQL 订单事实，
 * 再执行 Redis 库存幂等补偿；Redis 成功/幂等成功/可重试失败/致命失败映射为
 * {@link OutboxHandleResult}。本 Handler 不直接更新 Outbox 状态（最终状态由
 * OutboxPollingService 使用 lock_token CAS 完成），不执行 SREM。</p>
 *
 * <p>handledAt 为本次 Redis 补偿实际执行时间（通过可测试 Clock 在全部校验通过、
 * 调用适配器前固定一次，秒级），不是订单关闭时间 payload.closedAt；
 * payload.closedAt 仍保留并校验，只作为订单关闭事实语义。</p>
 *
 * <p>仅当 {@code linklife.trade.outbox.enabled=true} 时创建；默认关闭时不创建真实 Handler。</p>
 */
@Component
@ConditionalOnProperty(prefix = "linklife.trade.outbox", name = "enabled", havingValue = "true")
public class OrderClosedOutboxEventHandler implements OutboxBusinessHandler {

    private static final String AGGREGATE_TYPE = "VOUCHER_ORDER";
    private static final String EVENT_TYPE = "ORDER_CLOSED";
    private static final int EVENT_VERSION = 1;
    private static final String BUSINESS_KEY_PREFIX = "VOUCHER_ORDER:CLOSED:";
    private static final String BUSINESS_KEY_SUFFIX = ":V1";

    /**
     * 可测试时间源：默认系统时钟，测试通过注入固定 Clock 验证 handledAt。
     */
    private Clock clock = Clock.systemDefaultZone();

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private RedisOrderCloseCompensationAdapter compensationAdapter;

    @Override
    public String eventType() {
        return EVENT_TYPE;
    }

    @Override
    public int eventVersion() {
        return EVENT_VERSION;
    }

    @Override
    public OutboxHandleResult handle(OutboxEvent event) {
        String outerError = validateOuter(event);
        if (outerError != null) {
            return OutboxHandleResult.fatal(outerError);
        }

        OrderClosedEventPayload payload;
        try {
            payload = objectMapper.readValue(event.getPayload(), OrderClosedEventPayload.class);
        } catch (Exception e) {
            return OutboxHandleResult.fatal("PAYLOAD_INVALID");
        }
        String payloadError = validatePayload(event, payload);
        if (payloadError != null) {
            return OutboxHandleResult.fatal(payloadError);
        }

        String dbError = validateMySqlFact(payload);
        if (dbError != null) {
            if ("ORDER_READ_FAILED".equals(dbError)) {
                return OutboxHandleResult.retryable(dbError);
            }
            return OutboxHandleResult.fatal(dbError);
        }

        // handledAt = 本次 Redis 补偿实际执行时间，固定一次、秒级；
        // 不读取数据库当前时间，不在 Lua/Adapter 内重新获取系统时间。
        LocalDateTime handledAt = LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
        OrderCloseCompensationCommand command = new OrderCloseCompensationCommand(
                payload.orderId(), payload.userId(), payload.voucherId(),
                payload.eventId(), event.getBusinessKey(), EVENT_VERSION, handledAt);
        OrderCloseCompensationResult result = compensationAdapter.compensate(command);
        return mapCompensation(result);
    }

    /**
     * Outbox 外层契约校验：返回致命错误码或 null。
     */
    private String validateOuter(OutboxEvent event) {
        if (event == null) {
            return "OUTBOX_EXTERNAL_CONTRACT_INVALID";
        }
        if (!OutboxEventStatus.PROCESSING.name().equals(event.getStatus())) {
            return "OUTBOX_STATUS_INVALID";
        }
        if (event.getLockToken() == null || event.getLockToken().isBlank()) {
            return "OUTBOX_LOCK_TOKEN_MISSING";
        }
        if (!AGGREGATE_TYPE.equals(event.getAggregateType())) {
            return "OUTBOX_AGGREGATE_TYPE_INVALID";
        }
        if (!EVENT_TYPE.equals(event.getEventType())) {
            return "OUTBOX_EVENT_TYPE_INVALID";
        }
        if (event.getEventVersion() == null || event.getEventVersion() != EVENT_VERSION) {
            return "OUTBOX_EVENT_VERSION_INVALID";
        }
        if (event.getAggregateId() == null || event.getAggregateId() <= 0) {
            return "OUTBOX_AGGREGATE_ID_INVALID";
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            return "OUTBOX_EVENT_ID_MISSING";
        }
        String expectedBusinessKey = BUSINESS_KEY_PREFIX + event.getAggregateId() + BUSINESS_KEY_SUFFIX;
        if (!expectedBusinessKey.equals(event.getBusinessKey())) {
            return "OUTBOX_BUSINESS_KEY_INVALID";
        }
        if (event.getPayload() == null || event.getPayload().isBlank()) {
            return "OUTBOX_PAYLOAD_MISSING";
        }
        return null;
    }

    /**
     * payload 身份与业务事实校验：返回致命错误码或 null。
     */
    private String validatePayload(OutboxEvent event, OrderClosedEventPayload payload) {
        if (!event.getEventId().equals(payload.eventId())) {
            return "PAYLOAD_EVENT_ID_MISMATCH";
        }
        if (payload.eventVersion() != EVENT_VERSION) {
            return "PAYLOAD_EVENT_VERSION_MISMATCH";
        }
        if (payload.orderId() != event.getAggregateId()) {
            return "PAYLOAD_ORDER_ID_MISMATCH";
        }
        if (payload.userId() <= 0) {
            return "PAYLOAD_USER_ID_INVALID";
        }
        if (payload.voucherId() <= 0) {
            return "PAYLOAD_VOUCHER_ID_INVALID";
        }
        if (payload.toStatus() != VoucherOrderStatus.CANCELED.getCode()) {
            return "PAYLOAD_TO_STATUS_INVALID";
        }
        if (payload.triggerType() == null
                || (!OrderCloseTriggerType.USER_CANCEL.name().equals(payload.triggerType())
                && !OrderCloseTriggerType.TIMEOUT_CLOSE.name().equals(payload.triggerType()))) {
            return "PAYLOAD_TRIGGER_TYPE_INVALID";
        }
        if (payload.closedAt() == null) {
            return "PAYLOAD_CLOSED_AT_MISSING";
        }
        return null;
    }

    /**
     * MySQL 订单事实校验：返回错误码（ORDER_READ_FAILED 可重试，其余致命）或 null。
     */
    private String validateMySqlFact(OrderClosedEventPayload payload) {
        VoucherOrder order;
        try {
            order = voucherOrderMapper.selectOne(new LambdaQueryWrapper<VoucherOrder>()
                    .select(VoucherOrder::getId, VoucherOrder::getUserId,
                            VoucherOrder::getVoucherId, VoucherOrder::getStatus)
                    .eq(VoucherOrder::getId, payload.orderId()));
        } catch (DataAccessException e) {
            return "ORDER_READ_FAILED";
        }
        if (order == null) {
            return "ORDER_NOT_FOUND";
        }
        if (order.getStatus() == null
                || order.getStatus() != VoucherOrderStatus.CANCELED.getCode()) {
            return "ORDER_STATE_MISMATCH";
        }
        if (order.getUserId() == null || order.getUserId() != payload.userId()
                || order.getVoucherId() == null || order.getVoucherId() != payload.voucherId()) {
            return "ORDER_IDENTITY_MISMATCH";
        }
        return null;
    }

    private OutboxHandleResult mapCompensation(OrderCloseCompensationResult result) {
        switch (result.outcome()) {
            case SUCCESS:
                return OutboxHandleResult.success();
            case RETRYABLE_FAILURE:
                return OutboxHandleResult.retryable(result.errorCode());
            case FATAL_FAILURE:
                return OutboxHandleResult.fatal(result.errorCode());
        }
        return OutboxHandleResult.fatal("UNKNOWN_COMPENSATION_OUTCOME");
    }
}
