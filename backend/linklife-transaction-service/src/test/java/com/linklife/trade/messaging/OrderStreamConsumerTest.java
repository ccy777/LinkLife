package com.linklife.trade.messaging;

import com.linklife.trade.application.VoucherOrderTransactionalService;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.submission.OrderCreationFailureDecision;
import com.linklife.trade.submission.OrderCreationFailureService;
import com.linklife.trade.submission.RedisOrderSubmissionStatusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import io.lettuce.core.RedisCommandExecutionException;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderStreamConsumer 单元测试：Stream/消费组初始化、正常消费与 Pending 恢复、
 * retry/DLQ/ACK 全部 fail-closed 分支、消费循环调度与中断退出。不依赖真实 Redis/MySQL。
 */
class OrderStreamConsumerTest {

    private static final String STREAM = "transaction:stream.orders";
    private static final String GROUP = "g1";
    private static final String DEAD_LETTER_STREAM = "transaction:stream.orders.dlq";
    private static final String RETRY_HASH = "transaction:stream.orders:retry";
    private static final String RECORD_ID = "1-0";

    private OrderStreamConsumer consumer;
    private VoucherOrderTransactionalService transactionalService;
    private StringRedisTemplate redisTemplate;
    private StreamOperations<String, Object, Object> streamOps;
    private HashOperations<String, Object, Object> hashOps;
    private RedisOrderSubmissionStatusRepository statusRepository;
    private OrderCreationFailureService orderCreationFailureService;
    private SetOperations<String, String> setOps;

    @BeforeEach
    void setUp() {
        consumer = spy(new OrderStreamConsumer());
        transactionalService = mock(VoucherOrderTransactionalService.class);
        redisTemplate = mock(StringRedisTemplate.class);
        streamOps = mock(StreamOperations.class);
        hashOps = mock(HashOperations.class);
        statusRepository = mock(RedisOrderSubmissionStatusRepository.class);
        orderCreationFailureService = mock(OrderCreationFailureService.class);
        setOps = mock(SetOperations.class);

        when(redisTemplate.opsForStream()).thenReturn(streamOps);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(1L);
        when(setOps.isMember(anyString(), anyString())).thenReturn(false);
        when(setOps.add(anyString(), any(String[].class))).thenReturn(1L);
        when(transactionalService.process(any(VoucherOrder.class)))
                .thenReturn(VoucherOrderTransactionalService.ProcessResult.CREATED);

        ReflectionTestUtils.setField(consumer, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(consumer, "voucherOrderTransactionalService", transactionalService);
        ReflectionTestUtils.setField(consumer, "submissionStatusRepository", statusRepository);
        ReflectionTestUtils.setField(consumer, "orderCreationFailureService", orderCreationFailureService);
    }

    private MapRecord<String, Object, Object> orderRecord() {
        Map<Object, Object> value = new HashMap<>();
        value.put("id", 1001L);
        value.put("userId", 1L);
        value.put("voucherId", 2L);
        return StreamRecords.mapBacked(value)
                .withStreamKey(STREAM)
                .withId(RecordId.of(RECORD_ID));
    }

    @Test
    void normalMessageAcksWithOrderStreamOnSuccess() {
        consumer.handleOrderMessage(orderRecord());

        InOrder inOrder = inOrder(transactionalService, statusRepository, streamOps, hashOps);
        inOrder.verify(statusRepository).markProcessing(any(VoucherOrder.class));
        inOrder.verify(transactionalService).process(any(VoucherOrder.class));
        inOrder.verify(statusRepository).markPersisted(any(VoucherOrder.class));
        inOrder.verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));
        inOrder.verify(hashOps).delete(RETRY_HASH, RECORD_ID);
    }

    @Test
    void markProcessingFailureDoesNotInvokeTransactionOrAck() {
        doThrow(new IllegalStateException("redis down"))
                .when(statusRepository).markProcessing(any(VoucherOrder.class));

        assertThatThrownBy(() -> consumer.handleOrderMessage(orderRecord()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("redis down");

        verify(transactionalService, never()).process(any(VoucherOrder.class));
        verify(statusRepository, never()).markPersisted(any(VoucherOrder.class));
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void transactionFailureDoesNotMarkPersistedOrAck() {
        doThrow(new IllegalStateException("库存不足"))
                .when(transactionalService).process(any(VoucherOrder.class));

        assertThatThrownBy(() -> consumer.handleOrderMessage(orderRecord()))
                .isInstanceOf(IllegalStateException.class);

        verify(statusRepository).markProcessing(any(VoucherOrder.class));
        verify(statusRepository, never()).markPersisted(any(VoucherOrder.class));
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void markPersistedFailureDoesNotAck() {
        doThrow(new IllegalStateException("redis down"))
                .when(statusRepository).markPersisted(any(VoucherOrder.class));

        assertThatThrownBy(() -> consumer.handleOrderMessage(orderRecord()))
                .isInstanceOf(IllegalStateException.class);

        verify(transactionalService).process(any(VoucherOrder.class));
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void duplicateMessageStillMarksPersistedAndAcks() {
        consumer.handleOrderMessage(orderRecord());

        verify(statusRepository).markPersisted(any(VoucherOrder.class));
        verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));
        verify(hashOps).delete(RETRY_HASH, RECORD_ID);
    }

    @Test
    void conflictingExistingOrderIsNotMarkedPersistedNorAcked() {
        when(transactionalService.process(any(VoucherOrder.class)))
                .thenReturn(VoucherOrderTransactionalService.ProcessResult.CONFLICTING_EXISTING_ORDER);

        assertThatThrownBy(() -> consumer.handleOrderMessage(orderRecord()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("冲突而非幂等");

        verify(statusRepository).markProcessing(any(VoucherOrder.class));
        verify(statusRepository, never()).markPersisted(any(VoucherOrder.class));
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void maxRetryPathWritesDlqMarksFailedAcksThenCleans() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        InOrder inOrder = inOrder(streamOps, statusRepository, hashOps);
        inOrder.verify(streamOps).add(any(MapRecord.class));
        inOrder.verify(statusRepository).markFailed(any(VoucherOrder.class), eq("订单处理失败，请稍后重试或联系客服"));
        inOrder.verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));
        inOrder.verify(hashOps).delete(RETRY_HASH, RECORD_ID);
    }

    @Test
    void dlqWriteFailureSkipsMarkFailedAndAck() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(null);
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(statusRepository, never()).markFailed(any(VoucherOrder.class), anyString());
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    void markFailedFailureDoesNotAckOrCleanRetryCount() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        doThrow(new IllegalStateException("redis down"))
                .when(statusRepository).markFailed(any(VoucherOrder.class), anyString());
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(hashOps, never()).delete(anyString(), any());
        verify(consumer, times(1)).readPendingMessage();
    }

    @Test
    void ackFailureAfterMarkFailedDoesNotCleanRetryCount() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(0L);
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(statusRepository).markFailed(any(VoucherOrder.class), anyString());
        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    void retryBelowMaxDoesNotMarkFailed() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(1L);
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(statusRepository, never()).markFailed(any(VoucherOrder.class), anyString());
        verify(streamOps, never()).add(any(MapRecord.class));
    }

    @Test
    void pendingListMessageAcksWithOrderStreamOnSuccess() {
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();

        consumer.handlePendingList();

        verify(transactionalService).process(any(VoucherOrder.class));
        verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));
    }

    @Test
    void databaseExceptionDoesNotAck() {
        doThrow(new RuntimeException("db unavailable"))
                .when(transactionalService).process(any(VoucherOrder.class));

        assertThatThrownBy(() -> consumer.handleOrderMessage(orderRecord()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("db unavailable");

        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void consumerOnlyAcksAfterTransactionalServiceSuccess() {
        // 成功路径：ACK
        consumer.handleOrderMessage(orderRecord());
        verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));

        // 失败路径：不 ACK
        doThrow(new IllegalStateException("库存不足"))
                .when(transactionalService).process(any(VoucherOrder.class));
        assertThatThrownBy(() -> consumer.handleOrderMessage(orderRecord()))
                .isInstanceOf(IllegalStateException.class);
        verify(streamOps, times(1)).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void consumerExecutorFieldIsNotStatic() throws Exception {
        Field field = OrderStreamConsumer.class.getDeclaredField("orderStreamExecutor");
        assertThat(Modifier.isStatic(field.getModifiers())).isFalse();
    }

    @Test
    void contextCloseStopsConsumerBeforeRedisLifecycleShutdown() throws Exception {
        consumer.onContextClosed(null);

        Field field = OrderStreamConsumer.class.getDeclaredField("orderStreamExecutor");
        field.setAccessible(true);
        assertThat(((ExecutorService) field.get(consumer)).isShutdown()).isTrue();
    }

    @Test
    void ensureStreamGroupCreatesStreamAndGroupOnFirstStart() {
        when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString())).thenReturn("OK");

        consumer.ensureStreamGroup();

        verify(streamOps).createGroup(eq(STREAM), any(ReadOffset.class), eq(GROUP));
    }

    @Test
    void ensureStreamGroupIgnoresBusyGroup() {
        when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString()))
                .thenThrow(new RedisSystemException("Error in execution",
                        new RedisCommandExecutionException("BUSYGROUP Consumer Group name already exists")));

        consumer.ensureStreamGroup();

        verify(streamOps).createGroup(eq(STREAM), any(ReadOffset.class), eq(GROUP));
    }

    @Test
    void ensureStreamGroupRethrowsNonBusyGroupErrors() {
        when(streamOps.createGroup(anyString(), any(ReadOffset.class), anyString()))
                .thenThrow(new RedisSystemException("Error in execution",
                        new RedisCommandExecutionException("NOPERM this user has no permissions")));

        assertThatThrownBy(() -> consumer.ensureStreamGroup())
                .isInstanceOf(RedisSystemException.class);
    }

    @Test
    void pendingFailureBelowMaxDoesNotAckOrWriteDeadLetter() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(1L);
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(streamOps, never()).add(any(MapRecord.class));
        verify(consumer).backoffBeforePendingRetry(1L);
    }

    @Test
    void pendingFailureReachesMaxWritesDeadLetterAcksAndCleansRetryCount() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(streamOps).add(any(MapRecord.class));
        verify(statusRepository).markFailed(any(VoucherOrder.class), eq("订单处理失败，请稍后重试或联系客服"));
        verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));
        verify(hashOps).delete(RETRY_HASH, RECORD_ID);
    }

    @Test
    void deadLetterWriteFailureDoesNotAckOrCleanRetryCountAndExitsLoop() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        when(streamOps.add(any(MapRecord.class)))
                .thenThrow(new RedisSystemException("Error in execution",
                        new RedisCommandExecutionException("REDIS DOWN")));
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(hashOps, never()).delete(anyString(), any());
        verify(consumer, times(1)).readPendingMessage();
    }

    @Test
    void successfulMessageProcessingCleansRetryCount() {
        consumer.handleOrderMessage(orderRecord());

        verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));
        verify(hashOps).delete(RETRY_HASH, RECORD_ID);
    }

    @Test
    void deadLetterContainsOriginalOrderFieldsAndMetadata() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        ArgumentCaptor<MapRecord<String, Object, Object>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        Map<Object, Object> fields = captor.getValue().getValue();
        assertThat(fields.get("originalRecordId")).isEqualTo(RECORD_ID);
        assertThat(fields.get("originalStream")).isEqualTo(STREAM);
        assertThat(fields.get("retryCount")).isEqualTo("3");
        assertThat(fields.get("errorType")).isEqualTo("IllegalStateException");
        assertThat(fields.get("errorMessage")).isEqualTo("库存不足");
        assertThat(fields.get("failedAt")).isNotNull();
        assertThat(fields.get("id")).isEqualTo("1001");
        assertThat(fields.get("userId")).isEqualTo("1");
        assertThat(fields.get("voucherId")).isEqualTo("2");
    }

    @Test
    void deadLetterErrorMessageIsTruncated() {
        String longMessage = new String(new char[600]).replace('\0', 'x');
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException(longMessage))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        ArgumentCaptor<MapRecord<String, Object, Object>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        String errorMessage = (String) captor.getValue().getValue().get("errorMessage");
        assertThat(errorMessage).hasSize(500);
        assertThat(errorMessage).startsWith("xxx");
    }

    @Test
    void interruptedPendingRecoveryRestoresInterruptFlagAndExits() {
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new RuntimeException(new InterruptedException("interrupted")))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        try {
            consumer.handlePendingList();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(streamOps, never()).add(any(MapRecord.class));
        verify(consumer, times(1)).readPendingMessage();
    }

    @Test
    void pendingRecoveryExitsOnRedisCommandInterrupted() {
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new RedisSystemException("Redis command interrupted", new InterruptedException("interrupted")))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        try {
            consumer.handlePendingList();

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(streamOps, never()).add(any(MapRecord.class));
    }

    @Test
    void normalMessageAckReturnsZeroThrowsAndDoesNotCleanRetryCount() {
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(0L);

        assertThatThrownBy(() -> consumer.handleOrderMessage(orderRecord()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACK");

        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    void normalMessageAckReturnsNullThrowsAndDoesNotCleanRetryCount() {
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(null);

        assertThatThrownBy(() -> consumer.handleOrderMessage(orderRecord()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ACK");

        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    void deadLetterAckReturnsZeroDoesNotCleanAndExits() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(0L);
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(hashOps, never()).delete(anyString(), any());
        verify(consumer, times(1)).readPendingMessage();
    }

    @Test
    void deadLetterAckReturnsNullDoesNotCleanAndExits() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        when(streamOps.acknowledge(anyString(), anyString(), any(RecordId.class))).thenReturn(null);
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(hashOps, never()).delete(anyString(), any());
        verify(consumer, times(1)).readPendingMessage();
    }

    @Test
    void incrementRetryCountThrowsExitsWithoutAckOrDeadLetter() {
        when(hashOps.increment(anyString(), anyString(), anyLong()))
                .thenThrow(new RedisSystemException("Error in execution",
                        new RedisCommandExecutionException("REDIS DOWN")));
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(consumer, times(1)).readPendingMessage();
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(streamOps, never()).add(any(MapRecord.class));
        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    void incrementRetryCountReturnsNullExitsWithoutAckOrDeadLetter() {
        when(hashOps.increment(anyString(), anyString(), anyLong())).thenReturn(null);
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(consumer, times(1)).readPendingMessage();
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(streamOps, never()).add(any(MapRecord.class));
        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    void deadLetterWrittenWithAllNonNullFieldsWhenFieldsMissingAndMessageNull() {
        MapRecord<String, Object, Object> poisonRecord = StreamRecords.mapBacked(new HashMap<>())
                .withStreamKey(STREAM)
                .withId(RecordId.of(RECORD_ID));
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        doReturn(poisonRecord).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException())
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        ArgumentCaptor<MapRecord<String, Object, Object>> captor =
                ArgumentCaptor.forClass(MapRecord.class);
        verify(streamOps).add(captor.capture());
        Map<Object, Object> fields = captor.getValue().getValue();
        assertThat(fields.values()).doesNotContainNull();
        assertThat(fields.get("originalRecordId")).isEqualTo(RECORD_ID);
        assertThat(fields.get("originalStream")).isEqualTo(STREAM);
        assertThat(fields.get("retryCount")).isEqualTo("3");
        assertThat(fields.get("errorType")).isEqualTo("IllegalStateException");
        assertThat(fields.get("errorMessage")).isEqualTo("");
        assertThat(fields.get("id")).isEqualTo("");
        assertThat(fields.get("userId")).isEqualTo("");
        assertThat(fields.get("voucherId")).isEqualTo("");
    }

    @Test
    void maxRetryPathWritesDeadLetterThenAcksThenCleansRetryCount() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        InOrder inOrder = inOrder(streamOps, hashOps);
        inOrder.verify(streamOps).add(any(MapRecord.class));
        inOrder.verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));
        inOrder.verify(hashOps).delete(RETRY_HASH, RECORD_ID);
    }

    @Test
    void consumerLoopRunsPendingRecoveryBeforeFirstNormalRead() {
        doReturn(1000L).when(consumer).monotonicTimeNanos();
        doThrow(new RuntimeException(new InterruptedException("interrupted")))
                .when(consumer).readOrderMessage();

        try {
            consumer.runOrderConsumerLoop();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        InOrder inOrder = inOrder(consumer);
        inOrder.verify(consumer).handlePendingList();
        inOrder.verify(consumer).readOrderMessage();
        verify(consumer, times(1)).readOrderMessage();
    }

    @Test
    void consumerLoopRunsPendingRecoveryAgainOnIntervalWhileMessagesFlow() {
        doNothing().when(consumer).handleOrderMessage(any(MapRecord.class));
        doReturn(0L, 5_000_000_000L, 5_000_000_000L, 9_000_000_000L, 11_000_000_000L, 12_000_000_000L, 13_000_000_000L)
                .when(consumer).monotonicTimeNanos();
        doReturn(orderRecord(), orderRecord(), orderRecord(), orderRecord(), orderRecord())
                .doThrow(new RuntimeException(new InterruptedException("interrupted")))
                .when(consumer).readOrderMessage();

        try {
            consumer.runOrderConsumerLoop();
        } finally {
            Thread.interrupted();
        }

        verify(consumer, times(2)).handlePendingList();
        verify(consumer, times(6)).readOrderMessage();
        verify(consumer, times(5)).handleOrderMessage(any(MapRecord.class));
    }

    @Test
    void consumerLoopRunsPendingRecoveryAgainOnIntervalWhenReadsAreEmpty() {
        doReturn(0L, 5_000_000_000L, 5_000_000_000L, 11_000_000_000L, 12_000_000_000L, 13_000_000_000L)
                .when(consumer).monotonicTimeNanos();
        doReturn(null, null, null, null, null)
                .doThrow(new RuntimeException(new InterruptedException("interrupted")))
                .when(consumer).readOrderMessage();

        try {
            consumer.runOrderConsumerLoop();
        } finally {
            Thread.interrupted();
        }

        verify(consumer, times(2)).handlePendingList();
        verify(consumer, times(6)).readOrderMessage();
    }

    @Test
    void pendingReadExceptionExitsRecoveryWithoutInnerRetry() {
        doThrow(new RedisSystemException("Error in execution",
                new RedisCommandExecutionException("REDIS DOWN"))).when(consumer).readPendingMessage();

        consumer.handlePendingList();

        verify(consumer, times(1)).readPendingMessage();
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(streamOps, never()).add(any(MapRecord.class));
        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    void failClosedRecoveryRetriedInNextOuterCycle() {
        when(hashOps.increment(anyString(), anyString(), anyLong()))
                .thenThrow(new RedisSystemException("Error in execution",
                        new RedisCommandExecutionException("REDIS DOWN")));
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));
        doReturn(0L, 5_000_000_000L, 5_000_000_000L, 11_000_000_000L, 12_000_000_000L)
                .when(consumer).monotonicTimeNanos();
        doReturn(null, null, null)
                .doThrow(new RuntimeException(new InterruptedException("interrupted")))
                .when(consumer).readOrderMessage();

        try {
            consumer.runOrderConsumerLoop();
        } finally {
            Thread.interrupted();
        }

        verify(consumer, times(2)).handlePendingList();
        verify(consumer, times(2)).readPendingMessage();
        verify(hashOps, times(2)).increment(anyString(), anyString(), anyLong());
    }

    @Test
    void deadLetterFailureRecoveryRetriedInNextOuterCycle() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(null);
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));
        doReturn(0L, 5_000_000_000L, 5_000_000_000L, 11_000_000_000L, 12_000_000_000L)
                .when(consumer).monotonicTimeNanos();
        doReturn(null, null, null)
                .doThrow(new RuntimeException(new InterruptedException("interrupted")))
                .when(consumer).readOrderMessage();

        try {
            consumer.runOrderConsumerLoop();
        } finally {
            Thread.interrupted();
        }

        verify(consumer, times(2)).handlePendingList();
        verify(consumer, times(2)).readPendingMessage();
        verify(streamOps, times(2)).add(any(MapRecord.class));
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void interruptStopsConsumerLoopWithoutFurtherReads() {
        doReturn(1000L).when(consumer).monotonicTimeNanos();
        doThrow(new RuntimeException(new InterruptedException("interrupted")))
                .when(consumer).readOrderMessage();

        try {
            consumer.runOrderConsumerLoop();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(consumer, times(1)).readOrderMessage();
        verify(consumer, times(1)).handlePendingList();
    }

    @Test
    void consumerDoesNotDependOnWebServiceImpl() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/messaging/OrderStreamConsumer.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("import com.linklife.trade.service.IVoucherOrderService")
                .doesNotContain("import com.linklife.trade.service.impl.VoucherOrderServiceImpl")
                .doesNotContain("import com.linklife.trade.mapper.VoucherOrderMapper")
                .doesNotContain("import com.linklife.promotion.service.ISeckillVoucherService")
                .doesNotContain("import com.linklife.identity.security.UserHolder")
                .doesNotContain("import com.linklife.shared.redis.RedisIdWorker");
    }

    @Test
    void terminalCurrentOrderPersistedRestoresPersistedWithoutDlq() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.currentOrderPersisted());
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(statusRepository).markPersisted(any(VoucherOrder.class));
        verify(statusRepository, never()).markFailed(any(VoucherOrder.class), anyString());
        verify(streamOps, never()).add(any(MapRecord.class));
        verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));
        verify(hashOps).delete(RETRY_HASH, RECORD_ID);
    }

    @Test
    void terminalConflictingOtherOrderWritesDlqMarksFailedAcks() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.conflictingOtherOrder());
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(streamOps).add(any(MapRecord.class));
        verify(statusRepository).markFailed(any(VoucherOrder.class), eq("订单处理失败，请稍后重试或联系客服"));
        verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));
        verify(hashOps).delete(RETRY_HASH, RECORD_ID);
    }

    @Test
    void terminalRetryableCompensationKeepsPending() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.retryableCompensation());
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(streamOps, never()).add(any(MapRecord.class));
        verify(statusRepository, never()).markFailed(any(VoucherOrder.class), anyString());
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    void terminalFatalCompensationKeepsPending() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.fatalCompensation());
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(streamOps, never()).add(any(MapRecord.class));
        verify(statusRepository, never()).markFailed(any(VoucherOrder.class), anyString());
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    void terminalUncertainKeepsPending() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.uncertain());
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(streamOps, never()).add(any(MapRecord.class));
        verify(statusRepository, never()).markFailed(any(VoucherOrder.class), anyString());
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
        verify(hashOps, never()).delete(anyString(), any());
    }

    @Test
    void terminalClassificationServiceExceptionKeepsPending() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenThrow(new IllegalStateException("db down"));
        doReturn(orderRecord()).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        consumer.handlePendingList();

        verify(streamOps, never()).add(any(MapRecord.class));
        verify(statusRepository, never()).markFailed(any(VoucherOrder.class), anyString());
        verify(streamOps, never()).acknowledge(anyString(), anyString(), any(RecordId.class));
    }

    @Test
    void terminalDlqDedupSkipsDuplicateWriteOnRetry() {
        when(hashOps.increment(RETRY_HASH, RECORD_ID, 1L)).thenReturn(3L);
        when(streamOps.add(any(MapRecord.class))).thenReturn(RecordId.of("9-0"));
        when(orderCreationFailureService.classifyAndCompensate(any(VoucherOrder.class)))
                .thenReturn(OrderCreationFailureDecision.noMySqlOrder());
        doReturn(orderRecord()).doReturn(null).when(consumer).readPendingMessage();
        doThrow(new IllegalStateException("库存不足"))
                .when(consumer).handleOrderMessage(any(MapRecord.class));

        // 第一次终态：死信去重标记已存在（等价于此前 ACK 失败后的重试）
        when(setOps.isMember(anyString(), eq(RECORD_ID))).thenReturn(true);

        consumer.handlePendingList();

        verify(streamOps, never()).add(any(MapRecord.class));
        verify(statusRepository).markFailed(any(VoucherOrder.class), anyString());
        verify(streamOps).acknowledge(STREAM, GROUP, RecordId.of(RECORD_ID));
        verify(hashOps).delete(RETRY_HASH, RECORD_ID);
    }
}
