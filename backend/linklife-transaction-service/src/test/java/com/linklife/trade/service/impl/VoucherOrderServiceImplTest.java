package com.linklife.trade.service.impl;

import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.api.Result;
import com.linklife.trade.redis.TransactionRedisConstants;
import com.linklife.shared.redis.RedisIdWorker;
import com.linklife.trade.admission.RedisSeckillAdmissionAdapter;
import com.linklife.trade.admission.SeckillAdmissionDecision;
import com.linklife.trade.service.IVoucherOrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicReference;

import static com.linklife.trade.admission.SeckillAdmissionDecision.ACCEPTED;
import static com.linklife.trade.admission.SeckillAdmissionDecision.DUPLICATE_ORDER;
import static com.linklife.trade.admission.SeckillAdmissionDecision.ENDED;
import static com.linklife.trade.admission.SeckillAdmissionDecision.NOT_INITIALIZED;
import static com.linklife.trade.admission.SeckillAdmissionDecision.NOT_STARTED;
import static com.linklife.trade.admission.SeckillAdmissionDecision.OUT_OF_STOCK;
import static com.linklife.trade.admission.SeckillAdmissionDecision.UNAVAILABLE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 收缩后 VoucherOrderServiceImpl 单元测试：准入判定 → API Result 映射、
 * 显式时间参数透传、异步“已受理而非已落库”语义，以及职责收缩的结构检查。
 */
class VoucherOrderServiceImplTest {

    private VoucherOrderServiceImpl service;
    private RedisSeckillAdmissionAdapter admissionAdapter;
    private RedisIdWorker redisIdWorker;

    @BeforeEach
    void setUp() {
        service = spy(new VoucherOrderServiceImpl());
        admissionAdapter = mock(RedisSeckillAdmissionAdapter.class);
        redisIdWorker = mock(RedisIdWorker.class);

        when(redisIdWorker.nextId("order")).thenReturn(999L);

        ReflectionTestUtils.setField(service, "redisIdWorker", redisIdWorker);
        ReflectionTestUtils.setField(service, "seckillAdmissionAdapter", admissionAdapter);
    }

    private Result seckillAsUser(SeckillAdmissionDecision decision) {
        UserContext.set(1L);
        try {
            when(admissionAdapter.admit(anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                    .thenReturn(decision);
            return service.seckillVoucher(10L);
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void acceptedMeansAsyncSubmissionAcceptedAndReturnsOrderId() {
        Result result = seckillAsUser(ACCEPTED);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isEqualTo(999L);
    }

    @Test
    void outOfStockMapsToStockShortage() {
        Result result = seckillAsUser(OUT_OF_STOCK);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("库存不足");
        assertThat(result.getData()).isNull();
    }

    @Test
    void duplicateOrderMapsToCannotRepeat() {
        Result result = seckillAsUser(DUPLICATE_ORDER);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("不能重复下单");
        assertThat(result.getData()).isNull();
    }

    @Test
    void notInitializedMapsToNotInitialized() {
        Result result = seckillAsUser(NOT_INITIALIZED);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("秒杀活动未初始化");
        assertThat(result.getData()).isNull();
    }

    @Test
    void notStartedMapsToNotStarted() {
        Result result = seckillAsUser(NOT_STARTED);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("秒杀尚未开始");
        assertThat(result.getData()).isNull();
    }

    @Test
    void endedMapsToEnded() {
        Result result = seckillAsUser(ENDED);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("秒杀已经结束");
        assertThat(result.getData()).isNull();
    }

    @Test
    void unavailableMapsToTemporarilyUnavailable() {
        // null/未知/6/大值截断在适配器层统一映射为 UNAVAILABLE，此处验证消息映射
        Result result = seckillAsUser(UNAVAILABLE);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getErrorMsg()).isEqualTo("秒杀服务暂时不可用");
        assertThat(result.getData()).isNull();
    }

    @Test
    void nonSuccessDecisionsNeverProduceSuccess() {
        for (SeckillAdmissionDecision decision : new SeckillAdmissionDecision[]{
                OUT_OF_STOCK, DUPLICATE_ORDER, NOT_INITIALIZED, NOT_STARTED, ENDED, UNAVAILABLE}) {
            Result result = seckillAsUser(decision);
            assertThat(result.getSuccess()).isFalse();
            assertThat(result.getData()).isNull();
        }
    }

    @Test
    void servicePassesExplicitCurrentTimeToAdapter() {
        AtomicReference<Long> passedTimeRef = new AtomicReference<>();
        when(admissionAdapter.admit(anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    passedTimeRef.set(invocation.getArgument(3, Long.class));
                    return ACCEPTED;
                });
        UserContext.set(1L);
        try {
            long before = System.currentTimeMillis();
            service.seckillVoucher(10L);
            long after = System.currentTimeMillis();

            assertThat(passedTimeRef.get()).isBetween(before, after);
        } finally {
            UserContext.clear();
        }
    }

    @Test
    void oldBlockingQueueCommentBlockRemoved() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/service/impl/VoucherOrderServiceImpl.java")),
                StandardCharsets.UTF_8);

        assertThat(source).doesNotContain("ArrayBlockingQueue");
        assertThat(source).doesNotContain("orderTasks");
        assertThat(source).doesNotContain("1024 * 1024");
    }

    @Test
    void shrunkServiceImplRemovesStreamRedissonTransactionResponsibilities() throws Exception {
        for (Field field : VoucherOrderServiceImpl.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            assertThat(type.getSimpleName())
                    .as("VoucherOrderServiceImpl 不得再声明字段 %s", type.getSimpleName())
                    .isNotIn(
                            "ExecutorService",
                            "StringRedisTemplate",
                            "RedissonClient",
                            "TransactionTemplate",
                            "ISeckillVoucherService");
        }
        for (var method : VoucherOrderServiceImpl.class.getDeclaredMethods()) {
            assertThat(method.getAnnotations())
                    .as("VoucherOrderServiceImpl 不得再声明生命周期注解 %s", method.getName())
                    .noneMatch(a -> "PostConstruct".equals(a.annotationType().getSimpleName())
                            || "PreDestroy".equals(a.annotationType().getSimpleName()));
        }
    }

    @Test
    void serviceKeepsApiContractSignatures() throws Exception {
        assertThat(IVoucherOrderService.class.getMethod("seckillVoucher", Long.class))
                .isNotNull();
    }

    @Test
    void servicePassesSubmissionTtlToAdapter() {
        AtomicReference<Long> ttlRef = new AtomicReference<>();
        when(admissionAdapter.admit(anyLong(), anyLong(), anyLong(), anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    ttlRef.set(invocation.getArgument(4, Long.class));
                    return ACCEPTED;
                });
        UserContext.set(1L);
        try {
            service.seckillVoucher(10L);
            assertThat(ttlRef.get()).isEqualTo(TransactionRedisConstants.ORDER_SUBMISSION_TTL);
        } finally {
            UserContext.clear();
        }
    }
}
