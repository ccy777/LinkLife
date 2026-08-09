package com.linklife.trade.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.promotion.service.ISeckillVoucherService;
import com.linklife.trade.dto.OrderClosedEventPayload;
import com.linklife.trade.entity.OrderStatusLog;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.lifecycle.close.OrderCloseCommand;
import com.linklife.trade.lifecycle.close.OrderCloseOperatorType;
import com.linklife.trade.lifecycle.close.OrderCloseReasonCode;
import com.linklife.trade.lifecycle.close.OrderCloseResult;
import com.linklife.trade.lifecycle.close.OrderCloseTriggerType;
import com.linklife.trade.mapper.OrderStatusLogMapper;
import com.linklife.trade.mapper.OutboxEventMapper;
import com.linklife.trade.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 统一订单关闭事务服务（Stage 3E 017C）。
 *
 * <p>在同一个 MySQL 本地事务内完成：订单 UNPAID → CANCELED 条件 CAS → MySQL 秒杀券库存 +1 →
 * 状态日志写入 → 一条唯一 ORDER_CLOSED Outbox 事件写入；任一步失败整体回滚。</p>
 *
 * <p>本服务不依赖 UserHolder、Controller 或调度器；Redis 库存、Redis 资格与 MQ 不属于本事务
 * （Redis 幂等补偿由后续 017F 通过 Outbox 驱动）。不执行 SREM，不修改 Redis。</p>
 *
 * <p>约定：voucher_id 为 tb_seckill_voucher 主键，库存返还更新物理上最多影响 1 行；
 * 返回 0 行或异常行数均视为数据异常并抛异常回滚。</p>
 */
@Component
public class OrderCloseTransactionService {

    private static final String OUTBOX_AGGREGATE_TYPE = "VOUCHER_ORDER";
    private static final String OUTBOX_EVENT_TYPE = "ORDER_CLOSED";
    private static final int OUTBOX_EVENT_VERSION = 1;
    private static final String OUTBOX_STATUS_PENDING = "PENDING";
    private static final String OUTBOX_BUSINESS_KEY_PREFIX = "VOUCHER_ORDER:CLOSED:";
    private static final String OUTBOX_BUSINESS_KEY_SUFFIX = ":V1";
    private static final String STATUS_LOG_IDEM_KEY_PREFIX = "ORDER_STATUS:";
    private static final String REASON_DETAIL_USER_CANCEL = "用户主动取消";
    private static final String REASON_DETAIL_TIMEOUT = "支付超时自动关闭";
    private static final String CURRENT_READ_TAIL = "FOR UPDATE";

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private OrderStatusLogMapper orderStatusLogMapper;

    @Resource
    private OutboxEventMapper outboxEventMapper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 执行统一订单关闭事务。
     *
     * @param command 关闭命令（含触发来源、归属、cutoff、原因码与固定 now）
     * @return 冻结结果：CLOSED / ALREADY_CANCELED / NOT_FOUND / NOT_CLOSABLE
     * @throws IllegalStateException 影响行数异常、条件本应满足却 CAS=0、未知状态、写入失败（fail-closed，事务回滚）
     */
    public OrderCloseResult close(OrderCloseCommand command) {
        OrderCloseResult result = transactionTemplate.execute(status -> closeInTransaction(command));
        if (result == null) {
            throw new IllegalStateException(
                    "订单关闭事务返回空结果，fail-closed，orderId=" + command.orderId());
        }
        return result;
    }

    private OrderCloseResult closeInTransaction(OrderCloseCommand command) {
        VoucherOrder order = queryOrder(command);
        if (order == null) {
            return OrderCloseResult.NOT_FOUND;
        }
        OrderCloseResult early = earlyStatusResult(order);
        if (early != null) {
            return early;
        }

        int affected = executeCloseCas(command);
        if (affected == 1) {
            restoreStock(order);
            insertStatusLog(command, order);
            insertOutboxEvent(command, order);
            return OrderCloseResult.CLOSED;
        }
        if (affected < 0 || affected > 1) {
            throw new IllegalStateException(
                    "订单关闭 CAS 影响行数异常，fail-closed，orderId=" + command.orderId()
                            + ", affected=" + affected);
        }
        return resolveZeroRow(command);
    }

    /**
     * 按命令可见范围查询订单最小必要字段（id/user_id/voucher_id/status/create_time）。
     * 用户场景按 id+userId 查询，避免越权读取或泄露其他用户订单。
     */
    private VoucherOrder queryOrder(OrderCloseCommand command) {
        LambdaQueryWrapper<VoucherOrder> wrapper = new LambdaQueryWrapper<VoucherOrder>()
                .select(VoucherOrder::getId, VoucherOrder::getUserId, VoucherOrder::getVoucherId,
                        VoucherOrder::getStatus, VoucherOrder::getCreateTime)
                .eq(VoucherOrder::getId, command.orderId());
        if (command.triggerType() == OrderCloseTriggerType.USER_CANCEL) {
            wrapper.eq(VoucherOrder::getUserId, command.userId());
        }
        return voucherOrderMapper.selectOne(wrapper);
    }

    /**
     * 未写入前的状态预判：UNPAID 返回 null 继续走 CAS；CANCELED 幂等；PAID 等不可关闭；未知状态 fail-closed。
     */
    private OrderCloseResult earlyStatusResult(VoucherOrder order) {
        switch (parseStatus(order.getStatus())) {
            case UNPAID:
                return null;
            case CANCELED:
                return OrderCloseResult.ALREADY_CANCELED;
            case PAID:
            case USED:
            case REFUNDING:
            case REFUNDED:
                return OrderCloseResult.NOT_CLOSABLE;
        }
        throw new IllegalStateException("订单状态未知，fail-closed，orderId=" + order.getId());
    }

    /**
     * 订单关闭 CAS：
     * USER_CANCEL → id + user_id + status=UNPAID；
     * TIMEOUT_CLOSE → id + status=UNPAID + create_time<=cutoff。
     */
    private int executeCloseCas(OrderCloseCommand command) {
        LambdaUpdateWrapper<VoucherOrder> wrapper = new LambdaUpdateWrapper<VoucherOrder>()
                .eq(VoucherOrder::getId, command.orderId())
                .eq(VoucherOrder::getStatus, VoucherOrderStatus.UNPAID.getCode())
                .set(VoucherOrder::getStatus, VoucherOrderStatus.CANCELED.getCode())
                .set(VoucherOrder::getUpdateTime, command.now());
        if (command.triggerType() == OrderCloseTriggerType.USER_CANCEL) {
            wrapper.eq(VoucherOrder::getUserId, command.userId());
        } else {
            wrapper.le(VoucherOrder::getCreateTime, command.cutoff());
        }
        return voucherOrderMapper.update(null, wrapper);
    }

    /**
     * CAS=0 后的安全判定：按命令可见范围回查；仍为 UNPAID 且条件本应满足 → fail-closed 抛异常。
     * 必须使用 SELECT ... FOR UPDATE 当前锁定读，避免 InnoDB REPEATABLE READ 下普通 SELECT
     * 继续读取旧快照而误判并发关闭为数据异常。
     */
    private OrderCloseResult resolveZeroRow(OrderCloseCommand command) {
        VoucherOrder current = queryOrderForCurrentRead(command);
        if (current == null) {
            return OrderCloseResult.NOT_FOUND;
        }
        switch (parseStatus(current.getStatus())) {
            case CANCELED:
                return OrderCloseResult.ALREADY_CANCELED;
            case PAID:
            case USED:
            case REFUNDING:
            case REFUNDED:
                return OrderCloseResult.NOT_CLOSABLE;
            case UNPAID:
                if (command.triggerType() == OrderCloseTriggerType.TIMEOUT_CLOSE
                        && current.getCreateTime() != null
                        && current.getCreateTime().isAfter(command.cutoff())) {
                    return OrderCloseResult.NOT_CLOSABLE;
                }
                throw new IllegalStateException(
                        "订单关闭 CAS 返回 0 行但订单仍为 UNPAID，fail-closed，orderId=" + command.orderId());
        }
        throw new IllegalStateException("订单状态未知，fail-closed，orderId=" + command.orderId());
    }

    /**
     * CAS=0 后的当前锁定读回查：与首次普通查询明确分离，只用于最新状态判定。
     * USER_CANCEL 保留 id+userId 用户隔离；TIMEOUT_CLOSE 按 id 回查；
     * 固定静态尾句 FOR UPDATE，不接受外部拼接 SQL，只在本事务 callback 内调用。
     */
    private VoucherOrder queryOrderForCurrentRead(OrderCloseCommand command) {
        LambdaQueryWrapper<VoucherOrder> wrapper = new LambdaQueryWrapper<VoucherOrder>()
                .select(VoucherOrder::getId, VoucherOrder::getUserId, VoucherOrder::getVoucherId,
                        VoucherOrder::getStatus, VoucherOrder::getCreateTime)
                .eq(VoucherOrder::getId, command.orderId());
        if (command.triggerType() == OrderCloseTriggerType.USER_CANCEL) {
            wrapper.eq(VoucherOrder::getUserId, command.userId());
        }
        wrapper.last(CURRENT_READ_TAIL);
        return voucherOrderMapper.selectOne(wrapper);
    }

    /**
     * MySQL 秒杀券库存返还：仅在订单 CAS 成功后执行，voucher_id 为主键，物理上最多影响 1 行。
     */
    private void restoreStock(VoucherOrder order) {
        // 使用全限定泛型参数避免 import promotion.entity（trade 白名单不含 promotion.entity）；
        // voucher_id 为主键，更新物理上最多影响 1 行；返回 0 行即数据异常。
        int affected = seckillVoucherService.getBaseMapper().update(null,
                new UpdateWrapper<com.linklife.promotion.entity.SeckillVoucher>()
                        .setSql("stock = stock + 1")
                        .eq("voucher_id", order.getVoucherId()));
        if (affected != 1) {
            throw new IllegalStateException(
                    "秒杀券库存返还影响行数异常，fail-closed，voucherId=" + order.getVoucherId()
                            + ", affected=" + affected);
        }
    }

    /**
     * 状态日志写入：from=UNPAID、to=CANCELED、idempotency_key=ORDER_STATUS:{orderId}:1:4。
     */
    private void insertStatusLog(OrderCloseCommand command, VoucherOrder order) {
        OrderStatusLog log = new OrderStatusLog();
        log.setOrderId(order.getId());
        log.setFromStatus(VoucherOrderStatus.UNPAID.getCode());
        log.setToStatus(VoucherOrderStatus.CANCELED.getCode());
        log.setTriggerType(command.triggerType().name());
        log.setOperatorType(operatorType(command.triggerType()).name());
        log.setOperatorId(command.triggerType() == OrderCloseTriggerType.USER_CANCEL
                ? command.userId() : null);
        log.setReasonCode(command.reasonCode().name());
        log.setReasonDetail(reasonDetail(command.reasonCode()));
        log.setIdempotencyKey(STATUS_LOG_IDEM_KEY_PREFIX + order.getId()
                + ":" + VoucherOrderStatus.UNPAID.getCode()
                + ":" + VoucherOrderStatus.CANCELED.getCode());
        log.setCreatedTime(command.now());
        if (orderStatusLogMapper.insert(log) != 1) {
            throw new IllegalStateException("状态日志写入失败，fail-closed，orderId=" + order.getId());
        }
    }

    /**
     * Outbox 事件写入：event_id 为应用内 UUID；business_key 确定性；payload 最小字段；初始 PENDING。
     */
    private void insertOutboxEvent(OrderCloseCommand command, VoucherOrder order) {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setBusinessKey(OUTBOX_BUSINESS_KEY_PREFIX + order.getId() + OUTBOX_BUSINESS_KEY_SUFFIX);
        event.setAggregateType(OUTBOX_AGGREGATE_TYPE);
        event.setAggregateId(order.getId());
        event.setEventType(OUTBOX_EVENT_TYPE);
        event.setEventVersion(OUTBOX_EVENT_VERSION);
        event.setPayload(toPayloadJson(eventId, command, order));
        event.setStatus(OUTBOX_STATUS_PENDING);
        event.setRetryCount(0);
        event.setNextRetryTime(command.now());
        event.setCreatedTime(command.now());
        event.setUpdatedTime(command.now());
        if (outboxEventMapper.insert(event) != 1) {
            throw new IllegalStateException("Outbox 事件写入失败，fail-closed，orderId=" + order.getId());
        }
    }

    private String toPayloadJson(String eventId, OrderCloseCommand command, VoucherOrder order) {
        OrderClosedEventPayload payload = new OrderClosedEventPayload(
                eventId,
                OUTBOX_EVENT_VERSION,
                order.getId(),
                order.getUserId(),
                order.getVoucherId(),
                VoucherOrderStatus.CANCELED.getCode(),
                command.triggerType().name(),
                command.now());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Outbox payload 序列化失败，fail-closed，orderId=" + order.getId(), e);
        }
    }

    private VoucherOrderStatus parseStatus(Integer raw) {
        try {
            return VoucherOrderStatus.fromCode(raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("订单状态未知，fail-closed", e);
        }
    }

    private OrderCloseOperatorType operatorType(OrderCloseTriggerType triggerType) {
        return triggerType == OrderCloseTriggerType.USER_CANCEL
                ? OrderCloseOperatorType.USER
                : OrderCloseOperatorType.SYSTEM;
    }

    private String reasonDetail(OrderCloseReasonCode reasonCode) {
        return reasonCode == OrderCloseReasonCode.USER_CANCEL
                ? REASON_DETAIL_USER_CANCEL
                : REASON_DETAIL_TIMEOUT;
    }
}
