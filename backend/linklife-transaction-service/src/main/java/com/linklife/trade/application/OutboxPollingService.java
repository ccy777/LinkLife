package com.linklife.trade.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.lifecycle.outbox.OutboxEventHandler;
import com.linklife.trade.lifecycle.outbox.OutboxEventStatus;
import com.linklife.trade.lifecycle.outbox.OutboxHandleResult;
import com.linklife.trade.lifecycle.outbox.OutboxPollResult;
import com.linklife.trade.lifecycle.outbox.OutboxProperties;
import com.linklife.trade.mapper.OutboxEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 本地 Outbox 通用可靠处理框架（Stage 3E 017E）。
 *
 * <p>仅在 {@code linklife.trade.outbox.enabled=true} 时创建；默认关闭时应用不因缺 handler 启动失败，
 * 显式启用但无 {@link OutboxEventHandler} Bean 时依赖解析失败（fail-closed）。</p>
 *
 * <p>单轮固定 scanNow：先扫描租约已过期的 PROCESSING（优先回收，避免长期挂起），再用剩余容量
 * 扫描到期的 PENDING；均为第一页、无 count、无 offset、稳定排序。领取与结果更新全部使用数据库 CAS，
 * 结果更新以 lock_token 守卫，防止旧处理器在租约失效后覆盖新结果；不依赖分布式锁。</p>
 *
 * <p>017F 已提供真实 ORDER_CLOSED handler（Redis 库存幂等补偿，marker 无 TTL、不执行 SREM）；
 * 本服务不实现 MQ 发布，不直接补偿库存。</p>
 */
@Component
@ConditionalOnProperty(prefix = "linklife.trade.outbox", name = "enabled", havingValue = "true")
public class OutboxPollingService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingService.class);
    private static final String LEASE_EXPIRED = "LEASE_EXPIRED";
    private static final String HANDLER_NULL_RESULT = "HANDLER_NULL_RESULT";
    private static final String HANDLER_EXCEPTION = "HANDLER_EXCEPTION";

    @Resource
    private OutboxEventMapper outboxEventMapper;

    @Resource
    private OutboxProperties outboxProperties;

    @Resource
    private OutboxEventHandler outboxEventHandler;

    /**
     * 可测试时间源：默认系统时钟，测试通过注入固定 Clock 验证确定的 scanNow/租约/退避。
     */
    private Clock clock = Clock.systemDefaultZone();

    /**
     * 执行一轮 Outbox 轮询。
     *
     * @return 本轮不可变汇总结果
     * @throws IllegalStateException 影响行数异常、未知状态、未知处理结果类型（fail-closed）
     */
    public OutboxPollResult pollDueEvents() {
        // scanNow 只用于本轮候选查询，不得继续作为候选的领取时间或完成时间。
        LocalDateTime scanNow = now();
        int batches = 0;
        int scanned = 0;
        int claimed = 0;
        int succeeded = 0;
        int retried = 0;
        int dead = 0;
        int skipped = 0;
        int leaseLost = 0;
        boolean limitReached = false;

        while (true) {
            if (batches >= outboxProperties.getMaxBatchesPerRun()) {
                limitReached = true;
                break;
            }
            int batchSize = outboxProperties.getBatchSize();
            List<OutboxEvent> candidates = new ArrayList<>();
            List<OutboxEvent> expiredProcessing = queryExpiredProcessing(scanNow, batchSize);
            candidates.addAll(expiredProcessing);
            int remaining = batchSize - expiredProcessing.size();
            if (remaining > 0) {
                candidates.addAll(queryDuePending(scanNow, remaining));
            }
            if (candidates.isEmpty()) {
                break;
            }
            batches++;
            scanned += candidates.size();
            for (OutboxEvent candidate : candidates) {
                Outcome outcome = processCandidate(candidate);
                switch (outcome) {
                    case CLAIMED_SUCCESS -> {
                        claimed++;
                        succeeded++;
                    }
                    case CLAIMED_RETRIED -> {
                        claimed++;
                        retried++;
                    }
                    case CLAIMED_DEAD -> {
                        claimed++;
                        dead++;
                    }
                    case DEAD_WITHOUT_CLAIM -> dead++;
                    case SKIPPED -> skipped++;
                    case LEASE_LOST -> {
                        // 领取 CAS 已成功且 handler 已执行，只是最终 token 守卫更新 0 行：
                        // 该事件既属于已领取处理事件，也属于 leaseLost。
                        claimed++;
                        leaseLost++;
                    }
                }
            }
            if (candidates.size() < batchSize) {
                break;
            }
        }
        return new OutboxPollResult(batches, scanned, claimed, succeeded, retried, dead, skipped,
                leaseLost, limitReached);
    }

    /**
     * 处理单个候选：每条候选在真正执行领取/回收/过期 DEAD CAS 前单独获取一次 claimNow；
     * 过期 PROCESSING 先回收（直接 DEAD 或重新领取后执行 handler）；PENDING 先领取再执行 handler。
     */
    private Outcome processCandidate(OutboxEvent candidate) {
        LocalDateTime claimNow = now();
        if (OutboxEventStatus.PROCESSING.name().equals(candidate.getStatus())) {
            int newRetryCount = candidate.getRetryCount() + 1;
            if (newRetryCount >= outboxProperties.getMaxRetries()) {
                int affected = deadFromExpiredProcessing(candidate, claimNow, newRetryCount);
                return affected == 1 ? Outcome.DEAD_WITHOUT_CLAIM
                        : (affected == 0 ? Outcome.SKIPPED : failClosed(candidate.getId(), affected));
            }
            String newToken = UUID.randomUUID().toString();
            LocalDateTime lockedUntil = claimNow.plusSeconds(outboxProperties.getLeaseSeconds());
            int affected = reclaimExpiredProcessing(candidate, claimNow, newToken, lockedUntil, newRetryCount);
            if (affected == 0) {
                return Outcome.SKIPPED;
            }
            if (affected != 1) {
                failClosed(candidate.getId(), affected);
            }
            applyContext(candidate, newToken, lockedUntil, claimNow, newRetryCount);
            return handleClaimed(candidate);
        }
        if (OutboxEventStatus.PENDING.name().equals(candidate.getStatus())) {
            String newToken = UUID.randomUUID().toString();
            LocalDateTime lockedUntil = claimNow.plusSeconds(outboxProperties.getLeaseSeconds());
            int affected = claimPending(candidate, claimNow, newToken, lockedUntil);
            if (affected == 0) {
                return Outcome.SKIPPED;
            }
            if (affected != 1) {
                failClosed(candidate.getId(), affected);
            }
            applyContext(candidate, newToken, lockedUntil, claimNow, candidate.getRetryCount());
            return handleClaimed(candidate);
        }
        throw new IllegalStateException(
                "Outbox 候选状态非法，fail-closed，eventId=" + candidate.getEventId()
                        + ", status=" + candidate.getStatus());
    }

    /**
     * 执行 handler 并映射结果；获得最终处理结果后单独获取 finishNow，用于状态迁移时间。
     */
    private Outcome handleClaimed(OutboxEvent event) {
        OutboxHandleResult handleResult = invokeHandler(event);
        LocalDateTime finishNow = now();
        switch (handleResult.type()) {
            case SUCCESS:
                return completeSuccess(event, finishNow);
            case RETRYABLE_FAILURE:
                return completeRetryable(event, finishNow, handleResult.errorCode());
            case FATAL_FAILURE:
                return completeFatal(event, finishNow, handleResult.errorCode());
        }
        throw new IllegalStateException(
                "未知 Outbox 处理结果类型，fail-closed，eventId=" + event.getEventId());
    }

    private OutboxHandleResult invokeHandler(OutboxEvent event) {
        try {
            OutboxHandleResult result = outboxEventHandler.handle(event);
            if (result == null) {
                return OutboxHandleResult.retryable(HANDLER_NULL_RESULT);
            }
            return result;
        } catch (RuntimeException e) {
            log.warn("Outbox handler 异常 eventId={} businessKey={} errorType={}",
                    event.getEventId(), event.getBusinessKey(), e.getClass().getSimpleName());
            return OutboxHandleResult.retryable(HANDLER_EXCEPTION);
        }
    }

    private Outcome completeSuccess(OutboxEvent event, LocalDateTime finishNow) {
        int affected = outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, event.getId())
                .eq(OutboxEvent::getStatus, OutboxEventStatus.PROCESSING.name())
                .eq(OutboxEvent::getLockToken, event.getLockToken())
                .set(OutboxEvent::getStatus, OutboxEventStatus.SUCCESS.name())
                .set(OutboxEvent::getCompletedTime, finishNow)
                .set(OutboxEvent::getUpdatedTime, finishNow)
                .set(OutboxEvent::getLastErrorCode, null)
                .set(OutboxEvent::getLockToken, null)
                .set(OutboxEvent::getLockedUntil, null));
        return affected == 1 ? Outcome.CLAIMED_SUCCESS
                : (affected == 0 ? Outcome.LEASE_LOST : failClosed(event.getId(), affected));
    }

    private Outcome completeRetryable(OutboxEvent event, LocalDateTime finishNow, String errorCode) {
        int newRetryCount = event.getRetryCount() + 1;
        if (newRetryCount >= outboxProperties.getMaxRetries()) {
            return completeDead(event, finishNow, errorCode, newRetryCount);
        }
        LocalDateTime nextRetryTime = finishNow.plusNanos(computeBackoffMillis(newRetryCount) * 1_000_000L);
        int affected = outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, event.getId())
                .eq(OutboxEvent::getStatus, OutboxEventStatus.PROCESSING.name())
                .eq(OutboxEvent::getLockToken, event.getLockToken())
                .set(OutboxEvent::getStatus, OutboxEventStatus.PENDING.name())
                .set(OutboxEvent::getRetryCount, newRetryCount)
                .set(OutboxEvent::getNextRetryTime, nextRetryTime)
                .set(OutboxEvent::getLastErrorCode, errorCode)
                .set(OutboxEvent::getUpdatedTime, finishNow)
                .set(OutboxEvent::getLockToken, null)
                .set(OutboxEvent::getLockedUntil, null)
                .set(OutboxEvent::getCompletedTime, null));
        return affected == 1 ? Outcome.CLAIMED_RETRIED
                : (affected == 0 ? Outcome.LEASE_LOST : failClosed(event.getId(), affected));
    }

    private Outcome completeFatal(OutboxEvent event, LocalDateTime finishNow, String errorCode) {
        return completeDead(event, finishNow, errorCode, event.getRetryCount() + 1);
    }

    private Outcome completeDead(OutboxEvent event, LocalDateTime finishNow, String errorCode, int newRetryCount) {
        int affected = outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, event.getId())
                .eq(OutboxEvent::getStatus, OutboxEventStatus.PROCESSING.name())
                .eq(OutboxEvent::getLockToken, event.getLockToken())
                .set(OutboxEvent::getStatus, OutboxEventStatus.DEAD.name())
                .set(OutboxEvent::getRetryCount, newRetryCount)
                .set(OutboxEvent::getLastErrorCode, errorCode)
                .set(OutboxEvent::getCompletedTime, finishNow)
                .set(OutboxEvent::getUpdatedTime, finishNow)
                .set(OutboxEvent::getLockToken, null)
                .set(OutboxEvent::getLockedUntil, null));
        return affected == 1 ? Outcome.CLAIMED_DEAD
                : (affected == 0 ? Outcome.LEASE_LOST : failClosed(event.getId(), affected));
    }

    /**
     * 过期 PROCESSING 达到最大重试：直接 DEAD（不调用 handler），CAS 含旧 retry_count 条件。
     */
    private int deadFromExpiredProcessing(OutboxEvent candidate, LocalDateTime claimNow, int newRetryCount) {
        return outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, candidate.getId())
                .eq(OutboxEvent::getStatus, OutboxEventStatus.PROCESSING.name())
                .le(OutboxEvent::getLockedUntil, claimNow)
                .eq(OutboxEvent::getRetryCount, candidate.getRetryCount())
                .set(OutboxEvent::getStatus, OutboxEventStatus.DEAD.name())
                .set(OutboxEvent::getRetryCount, newRetryCount)
                .set(OutboxEvent::getLastErrorCode, LEASE_EXPIRED)
                .set(OutboxEvent::getCompletedTime, claimNow)
                .set(OutboxEvent::getUpdatedTime, claimNow)
                .set(OutboxEvent::getLockToken, null)
                .set(OutboxEvent::getLockedUntil, null));
    }

    /**
     * 过期 PROCESSING 未达上限：以新 token 重新领取并计一次失败（retry_count+1），CAS 含旧 retry_count 条件。
     */
    private int reclaimExpiredProcessing(OutboxEvent candidate, LocalDateTime claimNow,
                                         String newToken, LocalDateTime lockedUntil, int newRetryCount) {
        return outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, candidate.getId())
                .eq(OutboxEvent::getStatus, OutboxEventStatus.PROCESSING.name())
                .le(OutboxEvent::getLockedUntil, claimNow)
                .eq(OutboxEvent::getRetryCount, candidate.getRetryCount())
                .set(OutboxEvent::getStatus, OutboxEventStatus.PROCESSING.name())
                .set(OutboxEvent::getRetryCount, newRetryCount)
                .set(OutboxEvent::getLockToken, newToken)
                .set(OutboxEvent::getLockedUntil, lockedUntil)
                .set(OutboxEvent::getProcessingStartedTime, claimNow)
                .set(OutboxEvent::getUpdatedTime, claimNow)
                .set(OutboxEvent::getLastErrorCode, LEASE_EXPIRED)
                .set(OutboxEvent::getCompletedTime, null));
    }

    /**
     * 到期 PENDING 领取 CAS：不修改 retry_count。
     */
    private int claimPending(OutboxEvent candidate, LocalDateTime claimNow,
                             String newToken, LocalDateTime lockedUntil) {
        return outboxEventMapper.update(null, new LambdaUpdateWrapper<OutboxEvent>()
                .eq(OutboxEvent::getId, candidate.getId())
                .eq(OutboxEvent::getStatus, OutboxEventStatus.PENDING.name())
                .le(OutboxEvent::getNextRetryTime, claimNow)
                .set(OutboxEvent::getStatus, OutboxEventStatus.PROCESSING.name())
                .set(OutboxEvent::getLockToken, newToken)
                .set(OutboxEvent::getLockedUntil, lockedUntil)
                .set(OutboxEvent::getProcessingStartedTime, claimNow)
                .set(OutboxEvent::getUpdatedTime, claimNow)
                .set(OutboxEvent::getLastErrorCode, null));
    }

    private void applyContext(OutboxEvent event, String lockToken, LocalDateTime lockedUntil,
                              LocalDateTime claimNow, int retryCount) {
        event.setStatus(OutboxEventStatus.PROCESSING.name());
        event.setLockToken(lockToken);
        event.setLockedUntil(lockedUntil);
        event.setProcessingStartedTime(claimNow);
        event.setRetryCount(retryCount);
    }

    /**
     * 查询租约已过期的 PROCESSING：locked_until ASC, id ASC，第一页无 count。
     */
    private List<OutboxEvent> queryExpiredProcessing(LocalDateTime scanNow, int limit) {
        Page<OutboxEvent> page = outboxEventMapper.selectPage(
                new Page<>(1, limit, false),
                buildSelect(new LambdaQueryWrapper<OutboxEvent>())
                        .eq(OutboxEvent::getStatus, OutboxEventStatus.PROCESSING.name())
                        .le(OutboxEvent::getLockedUntil, scanNow)
                        .orderByAsc(OutboxEvent::getLockedUntil)
                        .orderByAsc(OutboxEvent::getId));
        return page == null ? List.of() : page.getRecords();
    }

    /**
     * 查询到期的 PENDING：next_retry_time ASC, id ASC，第一页无 count。
     */
    private List<OutboxEvent> queryDuePending(LocalDateTime scanNow, int limit) {
        Page<OutboxEvent> page = outboxEventMapper.selectPage(
                new Page<>(1, limit, false),
                buildSelect(new LambdaQueryWrapper<OutboxEvent>())
                        .eq(OutboxEvent::getStatus, OutboxEventStatus.PENDING.name())
                        .le(OutboxEvent::getNextRetryTime, scanNow)
                        .orderByAsc(OutboxEvent::getNextRetryTime)
                        .orderByAsc(OutboxEvent::getId));
        return page == null ? List.of() : page.getRecords();
    }

    private LambdaQueryWrapper<OutboxEvent> buildSelect(LambdaQueryWrapper<OutboxEvent> wrapper) {
        return wrapper.select(
                OutboxEvent::getId, OutboxEvent::getEventId, OutboxEvent::getBusinessKey,
                OutboxEvent::getAggregateType, OutboxEvent::getAggregateId, OutboxEvent::getEventType,
                OutboxEvent::getEventVersion, OutboxEvent::getPayload, OutboxEvent::getStatus,
                OutboxEvent::getRetryCount, OutboxEvent::getNextRetryTime, OutboxEvent::getLockToken,
                OutboxEvent::getLockedUntil, OutboxEvent::getProcessingStartedTime,
                OutboxEvent::getLastErrorCode, OutboxEvent::getCreatedTime,
                OutboxEvent::getUpdatedTime, OutboxEvent::getCompletedTime);
    }

    /**
     * 指数退避：delay = min(retryMaxDelay, retryBaseDelay * 2^(retryCount-1))，overflow-safe，无随机抖动。
     */
    long computeBackoffMillis(int retryCount) {
        long base = outboxProperties.getRetryBaseDelayMs();
        long max = outboxProperties.getRetryMaxDelayMs();
        if (retryCount <= 1) {
            return Math.min(max, base);
        }
        long delay = base;
        for (int i = 1; i < retryCount && delay < max; i++) {
            if (delay > max / 2) {
                delay = max;
                break;
            }
            delay *= 2;
        }
        return Math.min(max, delay);
    }

    private Outcome failClosed(long eventId, int affected) {
        throw new IllegalStateException(
                "Outbox 更新影响行数异常，fail-closed，eventId=" + eventId + ", affected=" + affected);
    }

    private LocalDateTime now() {
        // tb_outbox_event 时间字段为秒级 datetime：统一截断到秒，
        // 保证写入 Wrapper 的 lockedUntil/processingStartedTime/updatedTime/completedTime/nextRetryTime
        // 均为 nano==0，与数据库持久化精度一致。
        return LocalDateTime.now(clock).truncatedTo(ChronoUnit.SECONDS);
    }

    private enum Outcome {
        CLAIMED_SUCCESS,
        CLAIMED_RETRIED,
        CLAIMED_DEAD,
        DEAD_WITHOUT_CLAIM,
        SKIPPED,
        LEASE_LOST
    }
}
