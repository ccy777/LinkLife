package com.linklife.trade.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.lifecycle.close.OrderCloseCommand;
import com.linklife.trade.lifecycle.close.OrderCloseReasonCode;
import com.linklife.trade.lifecycle.close.OrderCloseResult;
import com.linklife.trade.lifecycle.close.OrderCloseTriggerType;
import com.linklife.trade.lifecycle.timeout.OrderTimeoutCloseResult;
import com.linklife.trade.lifecycle.timeout.OrderTimeoutProperties;
import com.linklife.trade.mapper.VoucherOrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时未支付订单扫描与自动关闭服务。
 *
 * <p>单轮开始时只冻结一次绝对时间；每批按
 * {@code status=UNPAID AND payment_due_at<=dueAtCutoff}、{@code payment_due_at ASC, id ASC}
 * 查询第一页（无 count、无 offset 深分页）；每条候选委托
 * {@link OrderCloseTransactionService} 在同一个 MySQL 本地事务内完成关闭
 * （订单 CAS + MySQL 库存 +1 + 状态日志 + Outbox），本服务不再维护单条关闭 CAS 与 0 行回查。</p>
 *
 * <p>多实例并发正确性依赖统一事务内核的数据库条件更新，不依赖分布式锁；
 * 内核异常原样传播并立即终止本轮；已成功关闭的前序订单不回滚，下一轮可继续扫描。</p>
 *
 * <p>本服务不直接操作库存；统一事务内核在成功关闭时返还 MySQL 库存并写入
 * {@code ORDER_CLOSED} Outbox，现有 Outbox Handler 再执行 Redis 幂等库存补偿；
 * 全生命周期一人一券资格 Set 不执行 SREM。</p>
 */
@Component
public class OrderTimeoutCloseService {

    private static final Logger log = LoggerFactory.getLogger(OrderTimeoutCloseService.class);

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private OrderTimeoutProperties orderTimeoutProperties;

    @Resource
    private OrderCloseTransactionService orderCloseTransactionService;

    /**
     * 可测试时间源：默认系统时钟，测试通过注入固定 Clock 验证确定的本轮时间。
     */
    private Clock clock = Clock.systemDefaultZone();

    /**
     * 执行一轮超时关闭扫描。
     *
     * @return 本轮不可变汇总结果
     * @throws IllegalStateException 影响行数异常、0 行后仍为 UNPAID、未知状态（fail-closed）
     */
    public OrderTimeoutCloseResult closeExpiredOrders() {
        Instant dueAtCutoff = clock.instant();
        LocalDateTime now = LocalDateTime.ofInstant(
                dueAtCutoff, orderTimeoutProperties.getZoneId());

        int batches = 0;
        int scanned = 0;
        int closed = 0;
        int skipped = 0;
        boolean limitReached = false;

        while (true) {
            if (batches >= orderTimeoutProperties.getMaxBatchesPerRun()) {
                limitReached = true;
                break;
            }
            List<VoucherOrder> candidates = queryNextBatch(dueAtCutoff);
            if (candidates.isEmpty()) {
                break;
            }
            batches++;
            scanned += candidates.size();
            for (VoucherOrder candidate : candidates) {
                OrderCloseCommand command = new OrderCloseCommand(
                        candidate.getId(), null, OrderCloseTriggerType.TIMEOUT_CLOSE,
                        dueAtCutoff, OrderCloseReasonCode.TIMEOUT_EXPIRED, now);
                OrderCloseResult result = orderCloseTransactionService.close(command);
                switch (result) {
                    case CLOSED -> closed++;
                    case ALREADY_CANCELED -> skipped++;
                    case NOT_FOUND -> {
                        log.warn("超时关闭：订单不存在，记为 skipped，orderId={}", candidate.getId());
                        skipped++;
                    }
                    case NOT_CLOSABLE -> skipped++;
                    case DATA_INCONSISTENT -> throw new IllegalStateException(
                            "超时关闭：订单数据不一致，fail-closed，orderId=" + candidate.getId());
                }
            }
            if (candidates.size() < orderTimeoutProperties.getBatchSize()) {
                break;
            }
        }
        return new OrderTimeoutCloseResult(
                dueAtCutoff, batches, scanned, closed, skipped, limitReached);
    }

    /**
     * 查询最早的一批过期 UNPAID 订单：第一页、无 count、只取 id/payment_due_at。
     */
    private List<VoucherOrder> queryNextBatch(Instant dueAtCutoff) {
        Page<VoucherOrder> page = voucherOrderMapper.selectPage(
                new Page<>(1, orderTimeoutProperties.getBatchSize(), false),
                new LambdaQueryWrapper<VoucherOrder>()
                        .select(VoucherOrder::getId, VoucherOrder::getPaymentDueAt)
                        .eq(VoucherOrder::getStatus, VoucherOrderStatus.UNPAID.getCode())
                        .le(VoucherOrder::getPaymentDueAt, dueAtCutoff)
                        .orderByAsc(VoucherOrder::getPaymentDueAt)
                        .orderByAsc(VoucherOrder::getId));
        return page == null ? List.of() : page.getRecords();
    }

}
