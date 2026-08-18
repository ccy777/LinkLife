package com.linklife.trade.lifecycle.timeout;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linklife.trade.application.OrderCloseTransactionService;
import com.linklife.trade.dto.OrderPaymentTimeoutEventPayload;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.lifecycle.close.OrderCloseCommand;
import com.linklife.trade.lifecycle.close.OrderCloseReasonCode;
import com.linklife.trade.lifecycle.close.OrderCloseResult;
import com.linklife.trade.lifecycle.close.OrderCloseTriggerType;
import com.linklife.trade.mapper.VoucherOrderMapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * RocketMQ timeout 消息的业务处理器。先核对 MySQL 事实，满足 frozen dueAt 后才调用统一关闭内核。
 */
@Component
public class OrderPaymentTimeoutMessageProcessor {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private OrderCloseTransactionService orderCloseTransactionService;

    @Resource
    private OrderTimeoutProperties orderTimeoutProperties;

    /** 测试可覆盖；生产默认使用配置业务时区。 */
    private Clock clock;

    public ProcessResult process(OrderPaymentTimeoutEventPayload payload) {
        VoucherOrder order;
        try {
            order = voucherOrderMapper.selectOne(new LambdaQueryWrapper<VoucherOrder>()
                    .select(VoucherOrder::getId, VoucherOrder::getUserId, VoucherOrder::getVoucherId,
                            VoucherOrder::getStatus, VoucherOrder::getCreateTime,
                            VoucherOrder::getPaymentDueAt)
                    .eq(VoucherOrder::getId, payload.orderId()));
        } catch (DataAccessException e) {
            throw new RetryableTimeoutMessageException("订单事实读取失败", e);
        }
        if (order == null) {
            return ProcessResult.NOT_FOUND;
        }
        if (order.getUserId() == null || order.getVoucherId() == null
                || order.getUserId() != payload.userId()
                || order.getVoucherId() != payload.voucherId()
                || order.getCreateTime() == null
                || !order.getCreateTime().equals(payload.createdAt())
                || order.getPaymentDueAt() == null
                || !order.getPaymentDueAt().equals(payload.dueAt())) {
            return ProcessResult.IDENTITY_MISMATCH;
        }

        VoucherOrderStatus status;
        try {
            status = VoucherOrderStatus.fromCode(order.getStatus());
        } catch (IllegalArgumentException e) {
            return ProcessResult.INVALID_ORDER_FACT;
        }
        if (status == VoucherOrderStatus.CANCELED) {
            return ProcessResult.ALREADY_CANCELED;
        }
        if (status != VoucherOrderStatus.UNPAID) {
            return ProcessResult.NOT_CLOSABLE;
        }

        Instant nowInstant = effectiveClock().instant();
        if (nowInstant.isBefore(payload.dueAt())) {
            return ProcessResult.TOO_EARLY;
        }
        LocalDateTime now = LocalDateTime.ofInstant(nowInstant, orderTimeoutProperties.getZoneId())
                .truncatedTo(ChronoUnit.SECONDS);
        OrderCloseResult result = orderCloseTransactionService.close(new OrderCloseCommand(
                payload.orderId(), null, OrderCloseTriggerType.TIMEOUT_CLOSE,
                nowInstant, OrderCloseReasonCode.TIMEOUT_EXPIRED, now));
        return switch (result) {
            case CLOSED -> ProcessResult.CLOSED;
            case ALREADY_CANCELED -> ProcessResult.ALREADY_CANCELED;
            case NOT_CLOSABLE -> ProcessResult.NOT_CLOSABLE;
            case NOT_FOUND -> ProcessResult.NOT_FOUND;
            case DATA_INCONSISTENT -> throw new IllegalStateException(
                    "订单 timeout 关闭返回数据不一致，orderId=" + payload.orderId());
        };
    }

    private Clock effectiveClock() {
        return clock == null ? Clock.system(orderTimeoutProperties.getZoneId()) : clock;
    }

    public enum ProcessResult {
        CLOSED,
        ALREADY_CANCELED,
        NOT_CLOSABLE,
        NOT_FOUND,
        TOO_EARLY,
        IDENTITY_MISMATCH,
        INVALID_ORDER_FACT
    }

    public static class RetryableTimeoutMessageException extends RuntimeException {
        public RetryableTimeoutMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
