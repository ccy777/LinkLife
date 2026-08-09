package com.linklife.trade.messaging;

import cn.hutool.core.bean.BeanUtil;
import com.linklife.trade.application.VoucherOrderTransactionalService;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.submission.OrderCreationFailureDecision;
import com.linklife.trade.submission.OrderCreationFailureService;
import com.linklife.trade.submission.RedisOrderSubmissionStatusRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 订单 Stream 消费者：负责 Stream/消费组生命周期、正常消费、Pending 恢复、
 * retry/DLQ/ACK 与线程生命周期；消息业务落库委托给 {@link VoucherOrderTransactionalService}。
 *
 * <p>不依赖 IVoucherOrderService / VoucherOrderServiceImpl / VoucherOrderMapper /
 * ISeckillVoucherService / UserHolder / RedisIdWorker。</p>
 */
@Slf4j
@Component
public class OrderStreamConsumer {

    /**
     * 订单消息 Stream 名称：正常读取、Pending List 读取与 ACK 必须统一使用
     */
    static final String ORDER_STREAM_KEY = "transaction:stream.orders";
    /**
     * 订单消息消费组
     */
    static final String ORDER_STREAM_GROUP = "g1";
    /**
     * 死信 Stream：达到最大重试次数的 Pending 消息写入此处
     */
    static final String ORDER_DEAD_LETTER_STREAM_KEY = "transaction:stream.orders.dlq";
    /**
     * 死信写入去重 Set：field 为原始 RecordId（独立幂等业务键，不依赖进程内状态）
     */
    static final String ORDER_DEAD_LETTER_DEDUP_KEY = "transaction:stream.orders:dlq:written";
    /**
     * 重试次数 Hash：field 为 Redis Stream RecordId，避免仅依赖进程内计数
     */
    static final String ORDER_RETRY_HASH_KEY = "transaction:stream.orders:retry";
    /**
     * Pending 消息最大失败次数，达到后写入死信
     */
    static final int MAX_PENDING_RETRY = 3;
    /**
     * Pending 恢复固定周期：启动时立即执行一次，之后按单调时钟周期触发
     */
    static final long PENDING_RECOVERY_INTERVAL_MILLIS = 5000L;
    private static final long NANOS_PER_MILLISECOND = 1_000_000L;
    /**
     * 死信 errorMessage 最大长度
     */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    private static final long BASE_RETRY_BACKOFF_MILLIS = 500L;
    private static final long MAX_RETRY_BACKOFF_MILLIS = 5000L;
    private static final String ORDER_STREAM_CONSUMER = "c1";
    /**
     * FAILED 提交状态对外固定安全文案，不携带内部异常、SQL 或路径
     */
    private static final String SAFE_FAILED_MESSAGE = "订单处理失败，请稍后重试或联系客服";

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private VoucherOrderTransactionalService voucherOrderTransactionalService;

    @Resource
    private RedisOrderSubmissionStatusRepository submissionStatusRepository;

    @Resource
    private OrderCreationFailureService orderCreationFailureService;

    /**
     * 非 static 单线程消费执行器：随 Spring Bean 实例独立，避免多实例共享线程池。
     */
    private final ExecutorService orderStreamExecutor = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;

    @PostConstruct
    private void init() {
        // 提交消费线程前幂等初始化 Stream 与消费组；失败时阻止消费者线程启动
        ensureStreamGroup();
        orderStreamExecutor.submit(new OrderHandler());
    }

    @PreDestroy
    private void destroy() {
        running = false;
        orderStreamExecutor.shutdownNow();
    }

    private class OrderHandler implements Runnable {

        @Override
        public void run() {
            runOrderConsumerLoop();
        }
    }

    /**
     * 外层消费主循环：启动时立即执行一次 Pending 恢复，之后按固定单调时钟周期触发；
     * Pending 恢复与正常消费统一由本循环调度，fail-closed 只结束单次恢复调用，不结束消费者。
     */
    void runOrderConsumerLoop() {
        long nextRecoveryDueNanos = 0L;
        while (running && !Thread.currentThread().isInterrupted()) {
            if (monotonicTimeNanos() >= nextRecoveryDueNanos) {
                triggerPendingRecovery();
                nextRecoveryDueNanos = monotonicTimeNanos() + PENDING_RECOVERY_INTERVAL_MILLIS * NANOS_PER_MILLISECOND;
            }
            try {
                MapRecord<String, Object, Object> record = readOrderMessage();
                if (record == null) {
                    continue;
                }
                handleOrderMessage(record);
            } catch (Exception e) {
                if (isInterrupted(e)) {
                    Thread.currentThread().interrupt();
                    log.info("检测到消费线程中断，退出正常消费循环");
                    break;
                }
                log.error("处理订单异常，立即触发一次 Pending 恢复", e);
                triggerPendingRecovery();
                nextRecoveryDueNanos = monotonicTimeNanos() + PENDING_RECOVERY_INTERVAL_MILLIS * NANOS_PER_MILLISECOND;
                backoffBeforePendingRetry(1L);
            }
        }
    }

    /**
     * Pending 恢复的防御性入口：单次恢复调用内的异常不结束消费者，交由外层周期重试。
     */
    private void triggerPendingRecovery() {
        try {
            handlePendingList();
        } catch (Exception e) {
            if (isInterrupted(e)) {
                Thread.currentThread().interrupt();
                return;
            }
            log.error("Pending 恢复调用异常，交由外层循环下个周期重试", e);
        }
    }

    /**
     * 单调时钟，便于测试注入；不依赖可能回拨的业务时间。
     */
    long monotonicTimeNanos() {
        return System.nanoTime();
    }

    /**
     * 幂等初始化订单 Stream 与消费组：首次启动时创建，已存在时忽略 BUSYGROUP。
     * 其他任何异常（连接失败、权限失败、命令错误等）继续抛出，阻止消费者启动。
     */
    void ensureStreamGroup() {
        try {
            stringRedisTemplate.opsForStream().createGroup(
                    ORDER_STREAM_KEY, ReadOffset.from("0"), ORDER_STREAM_GROUP);
            log.info("已创建订单 Stream 与消费组 stream={}, group={}", ORDER_STREAM_KEY, ORDER_STREAM_GROUP);
        } catch (DataAccessException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
            log.info("订单 Stream 消费组已存在，跳过创建 stream={}, group={}", ORDER_STREAM_KEY, ORDER_STREAM_GROUP);
        }
    }

    private boolean isBusyGroup(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("BUSYGROUP")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    /**
     * 读取正常消息，统一使用 ORDER_STREAM_KEY
     */
    MapRecord<String, Object, Object> readOrderMessage() {
        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                Consumer.from(ORDER_STREAM_GROUP, ORDER_STREAM_CONSUMER),
                StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.lastConsumed())
        );
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    /**
     * 读取 Pending List 消息，统一使用 ORDER_STREAM_KEY
     */
    MapRecord<String, Object, Object> readPendingMessage() {
        List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                Consumer.from(ORDER_STREAM_GROUP, ORDER_STREAM_CONSUMER),
                StreamReadOptions.empty().count(1),
                StreamOffset.create(ORDER_STREAM_KEY, ReadOffset.from("0"))
        );
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    /**
     * 统一消息处理入口：正常消息与 Pending List 消息复用，避免两套 ACK 判定逻辑漂移。
     * 顺序固定为：markProcessing -> 事务落库（返回领域结果）-> markPersisted -> ACK -> 清理 retry；
     * 同 user/voucher 已有不同 orderId 时判定冲突（不得标记 PERSISTED、不得 ACK），
     * 抛出异常进入 Pending，达到最大重试后由终态分类处理。
     * 任一步失败都不继续下一步（fail-closed）。
     */
    void handleOrderMessage(MapRecord<String, Object, Object> record) {
        VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
        submissionStatusRepository.markProcessing(voucherOrder);
        VoucherOrderTransactionalService.ProcessResult processResult =
                voucherOrderTransactionalService.process(voucherOrder);
        if (processResult == VoucherOrderTransactionalService.ProcessResult.CONFLICTING_EXISTING_ORDER) {
            throw new IllegalStateException(
                    "同 user/voucher 已有不同 orderId，判定冲突而非幂等，不得标记 PERSISTED：orderId="
                            + voucherOrder.getId());
        }
        submissionStatusRepository.markPersisted(voucherOrder);
        acknowledgeMessage(record.getId());
        cleanupRetryCount(record.getId());
    }

    /**
     * 统一 ACK：返回 null 或 0 时视为失败并抛出明确异常，调用方据此决定清理或退出。
     */
    private void acknowledgeMessage(RecordId recordId) {
        Long acknowledged = stringRedisTemplate.opsForStream().acknowledge(
                ORDER_STREAM_KEY, ORDER_STREAM_GROUP, recordId);
        if (acknowledged == null || acknowledged <= 0L) {
            throw new IllegalStateException("订单消息 ACK 失败（返回" + acknowledged
                    + "），保留原消息 recordId=" + recordId.getValue());
        }
    }

    /**
     * Pending List 恢复：与正常消息共用 handleOrderMessage。
     * 失败次数持久化在 ORDER_RETRY_HASH_KEY，达到 MAX_PENDING_RETRY 后先写死信，
     * 只有死信写入成功才 ACK 并删除重试计数；死信写入失败时不 ACK、不删除计数并退出循环。
     */
    void handlePendingList() {
        boolean exitRecovery = false;
        while (running && !Thread.currentThread().isInterrupted() && !exitRecovery) {
            MapRecord<String, Object, Object> record;
            try {
                record = readPendingMessage();
            } catch (Exception e) {
                if (isInterrupted(e)) {
                    Thread.currentThread().interrupt();
                    return;
                }
                log.error("读取 Pending List 异常，退出本次 Pending 恢复，交由外层周期重试", e);
                return;
            }
            if (record == null) {
                return;
            }
            try {
                handleOrderMessage(record);
            } catch (Exception e) {
                if (isInterrupted(e)) {
                    Thread.currentThread().interrupt();
                    return;
                }
                exitRecovery = handlePendingFailure(record, e);
            }
        }
    }

    /**
     * Pending 消息处理失败：持久化失败次数，未达上限时有限退避，达到上限时先写死信再 ACK。
     * 返回 true 表示应退出 Pending 恢复循环（死信写入失败或死信后 ACK 失败，禁止无间隔紧循环）。
     */
    private boolean handlePendingFailure(MapRecord<String, Object, Object> record, Exception failure) {
        RecordId recordId = record.getId();
        long retryCount;
        try {
            retryCount = incrementRetryCount(recordId);
        } catch (Exception e) {
            log.error("记录 Pending 重试次数失败，fail-closed 退出本次恢复：不 ACK、不写死信、不删除计数 recordId={}",
                    recordId.getValue(), e);
            return true;
        }
        if (retryCount < MAX_PENDING_RETRY) {
            log.warn("Pending 消息处理失败，第 {} 次失败，有限退避后重试 recordId={}", retryCount, recordId.getValue(), failure);
            backoffBeforePendingRetry(retryCount);
            return false;
        }
        return handleTerminalFailure(record, failure, retryCount);
    }

    /**
     * 达到最大 Pending 重试后的终态处理（任务书 3.4/5）。
     * 顺序冻结：补偿成功（或分类 A 不补偿）→ DLQ 写入 → 终态写入 → ACK → 清理 retry；
     * 任一步失败不继续后续步骤，原消息保持 Pending。
     */
    private boolean handleTerminalFailure(MapRecord<String, Object, Object> record,
                                          Exception failure, long retryCount) {
        RecordId recordId = record.getId();
        VoucherOrder order = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
        OrderCreationFailureDecision decision;
        try {
            decision = orderCreationFailureService.classifyAndCompensate(order);
        } catch (Exception e) {
            log.error("终态分类/补偿调用异常，保留 Pending 不 ACK、不写死信 recordId={}", recordId.getValue(), e);
            return true;
        }
        switch (decision.type()) {
            case CURRENT_ORDER_PERSISTED:
                return finalizePersisted(order, recordId);
            case CONFLICTING_OTHER_ORDER:
            case NO_MYSQL_ORDER:
                return finalizeFailed(order, record, failure, retryCount);
            case RETRYABLE_COMPENSATION:
                log.warn("终态补偿可重试失败，保留 Pending 不 ACK、不写死信 recordId={}", recordId.getValue());
                return true;
            case FATAL_COMPENSATION:
                log.error("终态补偿致命失败，保留 Pending、需要人工介入，不自动重复 +1 recordId={}", recordId.getValue());
                return true;
            case UNCERTAIN:
                log.warn("终态事实不确定（MySQL 读取失败/身份异常），不补偿、不 ACK、保留 Pending recordId={}",
                        recordId.getValue());
                return true;
        }
        return true;
    }

    /**
     * 终态分类 A：当前 orderId 已存在且身份一致，不执行失败补偿，恢复/保持 PERSISTED 后 ACK。
     */
    private boolean finalizePersisted(VoucherOrder order, RecordId recordId) {
        try {
            submissionStatusRepository.markPersisted(order);
        } catch (Exception e) {
            log.error("恢复 PERSISTED 失败，不 ACK、保留 Pending recordId={}", recordId.getValue(), e);
            return true;
        }
        try {
            acknowledgeMessage(recordId);
        } catch (Exception e) {
            log.error("恢复 PERSISTED 后 ACK 失败，保留 Pending recordId={}", recordId.getValue(), e);
            return true;
        }
        cleanupRetryCount(recordId);
        log.warn("终态分类 A：当前订单已落库，恢复 PERSISTED 并 ACK recordId={}", recordId.getValue());
        return false;
    }

    /**
     * 终态分类 B/C：Redis 补偿已成功（资格保留或释放），写入死信（独立幂等键去重）→
     * 标记 FAILED → ACK → 清理 retry。
     */
    private boolean finalizeFailed(VoucherOrder order, MapRecord<String, Object, Object> record,
                                   Exception failure, long retryCount) {
        RecordId recordId = record.getId();
        if (!ensureDeadLetterWritten(record, failure, retryCount)) {
            log.error("死信写入失败，原消息保留且不 ACK、不清理重试计数 recordId={}", recordId.getValue());
            return true;
        }
        try {
            submissionStatusRepository.markFailed(order, SAFE_FAILED_MESSAGE);
        } catch (Exception e) {
            log.error("死信写入成功后标记 FAILED 失败，不 ACK、不清理重试计数 recordId={}", recordId.getValue(), e);
            return true;
        }
        try {
            acknowledgeMessage(recordId);
        } catch (Exception e) {
            log.error("死信写入成功后 ACK 失败，原消息保留、不清理重试计数 recordId={}", recordId.getValue(), e);
            return true;
        }
        cleanupRetryCount(recordId);
        log.warn("终态分类 B/C：已补偿并写入死信、标记 FAILED、ACK recordId={}, retryCount={}",
                recordId.getValue(), retryCount);
        return false;
    }

    /**
     * 死信写入（带独立幂等去重键）：已写入过则跳过 XADD，避免 ACK 失败后的重复死信；
     * 去重标记写入失败时接受后续可能重复死信（补偿 marker 幂等保证库存不重复恢复）。
     */
    private boolean ensureDeadLetterWritten(MapRecord<String, Object, Object> record,
                                            Exception failure, long retryCount) {
        String originalRecordId = record.getId().getValue();
        try {
            Boolean alreadyWritten = stringRedisTemplate.opsForSet()
                    .isMember(ORDER_DEAD_LETTER_DEDUP_KEY, originalRecordId);
            if (Boolean.TRUE.equals(alreadyWritten)) {
                return true;
            }
            boolean written = writeDeadLetter(record, failure, retryCount);
            if (!written) {
                return false;
            }
            Long added = stringRedisTemplate.opsForSet().add(ORDER_DEAD_LETTER_DEDUP_KEY, originalRecordId);
            if (added == null) {
                log.warn("死信去重标记写入失败，接受后续可能重复死信（补偿 marker 幂等） recordId={}", originalRecordId);
            }
            return true;
        } catch (Exception e) {
            log.error("死信去重检查失败，保留 Pending 不 ACK recordId={}", originalRecordId, e);
            return false;
        }
    }

    private long incrementRetryCount(RecordId recordId) {
        Long count = stringRedisTemplate.opsForHash().increment(
                ORDER_RETRY_HASH_KEY, recordId.getValue(), 1L);
        if (count == null || count < 1L) {
            throw new IllegalStateException("记录订单重试次数失败：非法结果" + count
                    + "，recordId=" + recordId.getValue());
        }
        return count;
    }

    /**
     * 写入死信 Stream；写入结果为空或异常均视为失败。
     */
    boolean writeDeadLetter(MapRecord<String, Object, Object> record, Exception failure, long retryCount) {
        try {
            Map<Object, Object> value = record.getValue();
            Map<String, String> deadLetter = new LinkedHashMap<>();
            deadLetter.put("originalRecordId", record.getId().getValue());
            deadLetter.put("originalStream", ORDER_STREAM_KEY);
            deadLetter.put("failedAt", Instant.now().toString());
            deadLetter.put("retryCount", String.valueOf(retryCount));
            deadLetter.put("errorType", failure.getClass().getSimpleName());
            deadLetter.put("errorMessage", truncateErrorMessage(failure.getMessage()));
            deadLetter.put("id", nonNullString(value.get("id")));
            deadLetter.put("userId", nonNullString(value.get("userId")));
            deadLetter.put("voucherId", nonNullString(value.get("voucherId")));
            RecordId deadLetterId = stringRedisTemplate.opsForStream().add(
                    StreamRecords.mapBacked(deadLetter).withStreamKey(ORDER_DEAD_LETTER_STREAM_KEY));
            return deadLetterId != null;
        } catch (Exception e) {
            log.error("写入订单死信失败，原消息保留 recordId={}", record.getId().getValue(), e);
            return false;
        }
    }

    private String nonNullString(Object value) {
        return value == null ? "" : value.toString();
    }

    private String truncateErrorMessage(String message) {
        if (message == null) {
            return "";
        }
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message
                : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    /**
     * 成功后清理重试计数。删除失败只记录告警，不得把已成功落库的订单重新视为业务失败。
     */
    void cleanupRetryCount(RecordId recordId) {
        try {
            stringRedisTemplate.opsForHash().delete(ORDER_RETRY_HASH_KEY, recordId.getValue());
        } catch (Exception e) {
            log.warn("删除订单重试计数失败，不影响已成功的订单结果 recordId={}", recordId.getValue(), e);
        }
    }

    /**
     * 有限退避：delay = min(500ms * retryCount, 5s)，禁止无间隔快速重试。
     */
    void backoffBeforePendingRetry(long retryCount) {
        long delayMillis = Math.min(BASE_RETRY_BACKOFF_MILLIS * retryCount, MAX_RETRY_BACKOFF_MILLIS);
        try {
            Thread.sleep(delayMillis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 识别线程中断场景：当前线程中断标记、异常链中的 InterruptedException，
     * 或明确的中断类错误消息（如 Lettuce 的 "Redis command interrupted"）。
     */
    private boolean isInterrupted(Exception e) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        Throwable current = e;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("interrupt")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
