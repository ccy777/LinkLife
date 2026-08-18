package com.linklife.trade.submission;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.mapper.VoucherOrderMapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 订单创建失败终态分类与 Redis 补偿编排（Stage 3 017J-A）。
 *
 * <p>达到最大 Pending 重试后按任务书 3.4 重新读取 MySQL 事实并分类：
 * A 当前 orderId 已存在且身份一致 → 不补偿；B 同 user/voucher 已有不同 orderId →
 * 库存补偿（保留资格）；C MySQL 完全无订单 → 库存补偿（释放资格）；D 事实读取失败/身份异常 →
 * 不补偿（UNCERTAIN）。B/C 补偿结果映射为可重试/致命决策，由消费者决定后续步骤。</p>
 */
@Component
public class OrderCreationFailureService {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private OrderCreateFailureCompensationAdapter compensationAdapter;

    /**
     * 重新读取 MySQL 事实并执行必要的 Redis 补偿，返回终态分类决策。
     *
     * @param message 当前 Stream 消息订单（orderId/userId/voucherId）
     * @return 终态分类决策（B/C 时补偿已成功执行；RETRYABLE/FATAL/UNCERTAIN 时未执行终态）
     */
    public OrderCreationFailureDecision classifyAndCompensate(VoucherOrder message) {
        // 先按当前 orderId 读取事实（任务书 3.4 分类 A）
        VoucherOrder byOrderId;
        try {
            byOrderId = voucherOrderMapper.selectOne(new LambdaQueryWrapper<VoucherOrder>()
                    .select(VoucherOrder::getId, VoucherOrder::getUserId, VoucherOrder::getVoucherId)
                    .eq(VoucherOrder::getId, message.getId()));
        } catch (DataAccessException e) {
            return OrderCreationFailureDecision.uncertain();
        }
        if (byOrderId != null) {
            if (Objects.equals(byOrderId.getUserId(), message.getUserId())
                    && Objects.equals(byOrderId.getVoucherId(), message.getVoucherId())) {
                // A：当前 orderId 已存在且身份一致，不得补偿
                return OrderCreationFailureDecision.currentOrderPersisted();
            }
            // orderId 已存在但身份不一致：数据异常，事实不确定，不补偿
            return OrderCreationFailureDecision.uncertain();
        }

        // 按 (userId, voucherId) 读取已有订单（任务书 3.4 分类 B/C）
        VoucherOrder existing;
        try {
            existing = voucherOrderMapper.selectOne(new LambdaQueryWrapper<VoucherOrder>()
                    .select(VoucherOrder::getId)
                    .eq(VoucherOrder::getUserId, message.getUserId())
                    .eq(VoucherOrder::getVoucherId, message.getVoucherId()));
        } catch (DataAccessException e) {
            return OrderCreationFailureDecision.uncertain();
        }
        if (existing == null) {
            // C：MySQL 完全无该 user/voucher 订单 → 恢复库存 + 释放资格
            return compensate(message, OrderCreateCompensationMode.RESTORE_STOCK_AND_RELEASE_QUALIFICATION, 0L);
        }
        if (Objects.equals(existing.getId(), message.getId())) {
            return OrderCreationFailureDecision.currentOrderPersisted();
        }
        // B：已有不同 orderId → 恢复库存、保留资格
        return compensate(message, OrderCreateCompensationMode.RESTORE_STOCK_KEEP_QUALIFICATION, existing.getId());
    }

    private OrderCreationFailureDecision compensate(VoucherOrder message,
                                                    OrderCreateCompensationMode mode,
                                                    long existingOrderId) {
        LocalDateTime handledAt = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        OrderCreateFailureCompensationCommand command = new OrderCreateFailureCompensationCommand(
                message.getId(), message.getUserId(), message.getVoucherId(),
                mode, existingOrderId, handledAt, 1);
        OrderCreateFailureCompensationResult result = compensationAdapter.compensate(command);
        switch (result.outcome()) {
            case SUCCESS:
                return mode == OrderCreateCompensationMode.RESTORE_STOCK_AND_RELEASE_QUALIFICATION
                        ? OrderCreationFailureDecision.noMySqlOrder()
                        : OrderCreationFailureDecision.conflictingOtherOrder();
            case RETRYABLE_FAILURE:
                return OrderCreationFailureDecision.retryableCompensation();
            case FATAL_FAILURE:
                return OrderCreationFailureDecision.fatalCompensation();
        }
        return OrderCreationFailureDecision.uncertain();
    }
}
