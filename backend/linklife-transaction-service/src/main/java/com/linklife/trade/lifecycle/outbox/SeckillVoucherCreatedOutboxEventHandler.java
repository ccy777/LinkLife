package com.linklife.trade.lifecycle.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.promotion.service.ISeckillVoucherService;
import com.linklife.shared.event.SeckillVoucherCreatedEventPayload;
import com.linklife.trade.entity.OutboxEvent;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * SECKILL_VOUCHER_CREATED 事件业务 Handler（017J-B）。
 *
 * <p>执行顺序：Outbox 外层契约校验 → payload 校验 → MySQL 秒杀券事实校验 →
 * Redis 原子初始化（seckill-voucher-initialize.lua + 永久 marker）→ OutboxHandleResult。
 * 数据库访问异常可重试；秒杀券不存在、时间或身份冲突致命；全部通过后才执行 Redis。
 * currentStock 允许小于 initialStock（网络不确定重试时订单可能已扣减），不得要求相等。</p>
 */
@Component
@ConditionalOnProperty(prefix = "linklife.trade.outbox", name = "enabled", havingValue = "true")
public class SeckillVoucherCreatedOutboxEventHandler implements OutboxBusinessHandler {

    private static final String AGGREGATE_TYPE = "SECKILL_VOUCHER";
    private static final String EVENT_TYPE = "SECKILL_VOUCHER_CREATED";
    private static final int EVENT_VERSION = 1;
    private static final String BUSINESS_KEY_PREFIX = "SECKILL_VOUCHER:CREATED:";
    private static final String BUSINESS_KEY_SUFFIX = ":V1";

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SeckillVoucherInitializeAdapter initializeAdapter;

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

        SeckillVoucherCreatedEventPayload payload;
        try {
            payload = objectMapper.readValue(event.getPayload(), SeckillVoucherCreatedEventPayload.class);
        } catch (Exception e) {
            return OutboxHandleResult.fatal("PAYLOAD_INVALID");
        }
        String payloadError = validatePayload(event, payload);
        if (payloadError != null) {
            return OutboxHandleResult.fatal(payloadError);
        }

        String dbError = validateMySqlFact(payload);
        if (dbError != null) {
            if ("SECKILL_READ_FAILED".equals(dbError)) {
                return OutboxHandleResult.retryable(dbError);
            }
            return OutboxHandleResult.fatal(dbError);
        }

        LocalDateTime handledAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        SeckillVoucherInitializeCommand command = new SeckillVoucherInitializeCommand(
                payload.voucherId(), payload.initialStock(),
                payload.beginEpochMillis(), payload.endEpochMillis(),
                payload.eventId(), event.getBusinessKey(), handledAt, EVENT_VERSION);
        return mapInitialize(initializeAdapter.initialize(command));
    }

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

    private String validatePayload(OutboxEvent event, SeckillVoucherCreatedEventPayload payload) {
        if (!event.getEventId().equals(payload.eventId())) {
            return "PAYLOAD_EVENT_ID_MISMATCH";
        }
        if (payload.voucherId() != event.getAggregateId()) {
            return "PAYLOAD_VOUCHER_ID_MISMATCH";
        }
        if (payload.eventVersion() != EVENT_VERSION) {
            return "PAYLOAD_EVENT_VERSION_MISMATCH";
        }
        if (payload.initialStock() < 0) {
            return "PAYLOAD_INITIAL_STOCK_INVALID";
        }
        if (payload.beginEpochMillis() <= 0 || payload.endEpochMillis() <= 0
                || payload.beginEpochMillis() >= payload.endEpochMillis()) {
            return "PAYLOAD_BEGIN_END_INVALID";
        }
        if (payload.createdAt() == null) {
            return "PAYLOAD_CREATED_AT_MISSING";
        }
        return null;
    }

    private String validateMySqlFact(SeckillVoucherCreatedEventPayload payload) {
        try {
            // 不 import promotion.entity（trade 白名单只允许 promotion.service）：
            // 通过 var 推断实体类型访问最小字段
            var voucher = seckillVoucherService.getById(payload.voucherId());
            if (voucher == null) {
                return "SECKILL_VOUCHER_NOT_FOUND";
            }
            if (voucher.getVoucherId() == null || voucher.getVoucherId() != payload.voucherId()) {
                return "SECKILL_IDENTITY_MISMATCH";
            }
            if (voucher.getStock() == null || voucher.getStock() < 0
                    || voucher.getStock() > payload.initialStock()) {
                return "SECKILL_STOCK_INVALID";
            }
            if (voucher.getBeginTime() == null || voucher.getEndTime() == null) {
                return "SECKILL_TIME_MISMATCH";
            }
            long beginMillis = voucher.getBeginTime()
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            long endMillis = voucher.getEndTime()
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (beginMillis != payload.beginEpochMillis() || endMillis != payload.endEpochMillis()) {
                return "SECKILL_TIME_MISMATCH";
            }
            return null;
        } catch (DataAccessException e) {
            return "SECKILL_READ_FAILED";
        }
    }

    private OutboxHandleResult mapInitialize(SeckillVoucherInitializeResult result) {
        switch (result.outcome()) {
            case SUCCESS:
                return OutboxHandleResult.success();
            case RETRYABLE_FAILURE:
                return OutboxHandleResult.retryable(result.errorCode());
            case FATAL_FAILURE:
                return OutboxHandleResult.fatal(result.errorCode());
        }
        return OutboxHandleResult.fatal("UNKNOWN_INITIALIZE_OUTCOME");
    }
}
