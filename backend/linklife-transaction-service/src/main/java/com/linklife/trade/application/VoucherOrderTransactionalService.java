package com.linklife.trade.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.promotion.service.ISeckillVoucherService;
import com.linklife.shared.outbox.OutboxPublishCommand;
import com.linklife.shared.outbox.OutboxPublisher;
import com.linklife.trade.dto.OrderPaymentTimeoutEventPayload;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.lifecycle.VoucherOrderStatus;
import com.linklife.trade.lifecycle.timeout.OrderPaymentTimeoutEvent;
import com.linklife.trade.lifecycle.timeout.OrderTimeoutProperties;
import com.linklife.trade.lifecycle.timeout.OrderTimeoutRocketMqProperties;
import com.linklife.trade.mapper.VoucherOrderMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.annotation.Resource;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

/**
 * 订单事务落库服务：负责用户级订单锁与 MySQL 事务（查重 → 条件扣库存 → 保存订单）。
 *
 * <p>不依赖 Redis Stream / StringRedisTemplate / RedisIdWorker / UserHolder /
 * RedisSeckillAdmissionAdapter / Web Result。精确订单身份：同 (userId, voucherId) 已有订单
 * 必须区分“相同 orderId 幂等”与“不同 orderId 冲突”，禁止用数量查询把所有已有订单视为当前消息幂等。
 * 唯一约束竞争时原事务整体回滚，回滚后重新读取事实分类。</p>
 */
@Slf4j
@Component
public class VoucherOrderTransactionalService {

    private static final String LOCK_ORDER_KEY_PREFIX = "transaction:lock:order:";

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private OutboxPublisher outboxPublisher;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private OrderTimeoutProperties orderTimeoutProperties;

    @Resource
    private OrderTimeoutRocketMqProperties rocketMqProperties;

    /** 测试可覆盖；生产默认使用配置的业务时区。 */
    private Clock clock;

    /**
     * 订单落库领域结果（冻结语义）。
     */
    public enum ProcessResult {
        /** 当前 orderId 新建成功 */
        CREATED,
        /** MySQL 已存在相同 orderId/user/voucher（幂等） */
        IDEMPOTENT_SAME_ORDER,
        /** 同 user/voucher 已有不同 orderId（多余准入，不得标记当前 orderId PERSISTED） */
        CONFLICTING_EXISTING_ORDER
    }

    /**
     * 加用户订单锁后处理订单；未获取锁时抛异常（消息不得 ACK，交由消费者保留重试）。
     * finally 中仅在当前线程持锁时解锁，避免误删其他线程的锁。
     */
    public ProcessResult process(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        RLock redisLock = redissonClient.getLock(LOCK_ORDER_KEY_PREFIX + userId);
        boolean isLock = redisLock.tryLock();
        if (!isLock) {
            throw new IllegalStateException("未获取到用户订单锁，保留消息重试");
        }
        try {
            return createVoucherOrder(voucherOrder);
        } finally {
            if (redisLock.isHeldByCurrentThread()) {
                redisLock.unlock();
            }
        }
    }

    /**
     * 在真实 Spring 事务中执行：查重 → 条件扣库存 → 保存订单。
     * 唯一约束竞争时第一个事务整体回滚，回滚后重新读取事实并按 orderId 分类。
     */
    private ProcessResult createVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        try {
            return transactionTemplate.execute(status -> createVoucherOrderInTransaction(voucherOrder));
        } catch (DuplicateKeyException e) {
            VoucherOrder existing = findExistingByUserVoucher(userId, voucherId);
            if (existing == null) {
                throw new IllegalStateException("唯一约束冲突但订单不存在，无法确认幂等", e);
            }
            if (Objects.equals(existing.getId(), voucherOrder.getId())) {
                log.info("重复消息幂等成功（唯一约束冲突回滚后确认相同 orderId），orderId={}, userId={}, voucherId={}",
                        voucherOrder.getId(), userId, voucherId);
                return ProcessResult.IDEMPOTENT_SAME_ORDER;
            }
            log.warn("同 user/voucher 已有不同 orderId，判定冲突而非幂等，messageOrderId={}, existingOrderId={}",
                    voucherOrder.getId(), existing.getId());
            return ProcessResult.CONFLICTING_EXISTING_ORDER;
        }
    }

    private ProcessResult createVoucherOrderInTransaction(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        // 1. 查询同一用户、同一优惠券已有订单的最小事实（uk_user_voucher 唯一，最多一行）
        VoucherOrder existing = findExistingByUserVoucher(userId, voucherId);
        // 2. 精确订单身份分类
        if (existing != null) {
            if (Objects.equals(existing.getId(), voucherOrder.getId())) {
                // 相同 orderId：幂等成功，不扣库存
                log.info("重复消息幂等成功（查重发现相同 orderId），orderId={}, userId={}, voucherId={}",
                        voucherOrder.getId(), userId, voucherId);
                return ProcessResult.IDEMPOTENT_SAME_ORDER;
            }
            // 不同 orderId：冲突（多余准入），不扣库存、不得把当前 orderId 标记 PERSISTED
            log.warn("同 user/voucher 已有不同 orderId，判定冲突而非幂等，messageOrderId={}, existingOrderId={}",
                    voucherOrder.getId(), existing.getId());
            return ProcessResult.CONFLICTING_EXISTING_ORDER;
        }

        // 3. 每个订单都在创建事务内冻结创建时刻与支付到期事实。MQ disabled 只是不写
        // timeout publish intent；Scheduler 仍按同一 payment_due_at 执行旧的兜底职责。
        OrderTimeFacts timeFacts = currentOrderTimeFacts();
        voucherOrder.setStatus(VoucherOrderStatus.UNPAID.getCode());
        voucherOrder.setCreateTime(timeFacts.createdAt());
        voucherOrder.setPaymentDueAt(timeFacts.paymentDueAt());
        voucherOrder.setUpdateTime(timeFacts.createdAt());

        // 4. 无已有订单：条件扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") // set stock = stock - 1
                .eq("voucher_id", voucherId).gt("stock", 0) // where voucher_id = ? and stock > 0
                .update();
        if (!success) {
            // 库存不足且无法确认订单已存在：不 ACK
            throw new IllegalStateException("库存不足");
        }

        // 5. 保存订单；唯一约束冲突会让本事务整体回滚
        int saved = voucherOrderMapper.insert(voucherOrder);
        if (saved != 1) {
            // 保存失败（insert 影响行数不为 1 且未抛异常）：抛出异常触发事务回滚，消息保持不 ACK
            throw new IllegalStateException("订单保存失败，事务回滚");
        }
        // 6. 与新订单同一个 MySQL 本地事务可靠记录 timeout intent。
        if (isRocketMqTimeoutEnabled()) {
            publishTimeoutIntent(voucherOrder, timeFacts);
        }
        return ProcessResult.CREATED;
    }

    private void publishTimeoutIntent(VoucherOrder order, OrderTimeFacts timeFacts) {
        String eventId = UUID.randomUUID().toString();
        OrderPaymentTimeoutEventPayload payload = new OrderPaymentTimeoutEventPayload(
                eventId, OrderPaymentTimeoutEvent.EVENT_VERSION,
                order.getId(), order.getUserId(), order.getVoucherId(),
                timeFacts.createdAt(), timeFacts.createdAtInstant(), timeFacts.paymentDueAt());
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Timeout intent payload 序列化失败，事务回滚，orderId=" + order.getId(), e);
        }
        outboxPublisher.publish(new OutboxPublishCommand(
                OrderPaymentTimeoutEvent.AGGREGATE_TYPE,
                order.getId(),
                OrderPaymentTimeoutEvent.EVENT_TYPE,
                OrderPaymentTimeoutEvent.EVENT_VERSION,
                OrderPaymentTimeoutEvent.businessKey(order.getId()),
                payloadJson,
                eventId,
                timeFacts.createdAt()));
    }

    private OrderTimeFacts currentOrderTimeFacts() {
        Clock effectiveClock = clock == null ? Clock.systemUTC() : clock;
        Instant createdAtInstant = effectiveClock.instant().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime createdAt = LocalDateTime.ofInstant(
                createdAtInstant, orderTimeoutProperties.getZoneId());
        return new OrderTimeFacts(createdAt, createdAtInstant,
                createdAtInstant.plus(orderTimeoutProperties.getPaymentTimeout()));
    }

    private record OrderTimeFacts(
            LocalDateTime createdAt, Instant createdAtInstant, Instant paymentDueAt) {
    }

    private boolean isRocketMqTimeoutEnabled() {
        // 生产由 Spring 必定注入；null 仅兼容不启动容器的既有纯单元测试夹具，等价于默认 disabled。
        return rocketMqProperties != null && rocketMqProperties.isEnabled();
    }

    /**
     * 查询同一 (userId, voucherId) 已有订单的最小事实（仅 id）。
     * uk_user_voucher 唯一约束保证最多一行；返回 null 表示无已有订单。
     */
    private VoucherOrder findExistingByUserVoucher(Long userId, Long voucherId) {
        return voucherOrderMapper.selectOne(
                new LambdaQueryWrapper<VoucherOrder>()
                        .select(VoucherOrder::getId)
                        .eq(VoucherOrder::getUserId, userId)
                        .eq(VoucherOrder::getVoucherId, voucherId));
    }
}
