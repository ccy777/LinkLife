package com.linklife.trade.application;

import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.trade.dto.OrderSubmissionStatusDTO;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.mapper.VoucherOrderMapper;
import com.linklife.trade.submission.OrderSubmissionRecord;
import com.linklife.trade.submission.OrderSubmissionState;
import com.linklife.trade.submission.RedisOrderSubmissionStatusRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderSubmissionStatusService 单元测试：MySQL 优先、当前用户隔离、UNKNOWN 语义、
 * Redis/MySQL 异常 fail-closed（服务暂不可用）、DTO 不暴露 userId。不连接真实 Redis/MySQL。
 */
class OrderSubmissionStatusServiceTest {

    private OrderSubmissionStatusService service;
    private VoucherOrderMapper mapper;
    private RedisOrderSubmissionStatusRepository repository;

    @BeforeEach
    void setUp() {
        service = new OrderSubmissionStatusService();
        mapper = mock(VoucherOrderMapper.class);
        repository = mock(RedisOrderSubmissionStatusRepository.class);
        ReflectionTestUtils.setField(service, "voucherOrderMapper", mapper);
        ReflectionTestUtils.setField(service, "submissionStatusRepository", repository);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private void asUser(long userId) {
        UserContext.set(userId);
    }

    private VoucherOrder order(long id, long userId, LocalDateTime createTime, LocalDateTime updateTime) {
        VoucherOrder order = new VoucherOrder();
        order.setId(id);
        order.setUserId(userId);
        order.setVoucherId(2L);
        order.setCreateTime(createTime);
        order.setUpdateTime(updateTime);
        return order;
    }

    private long toMillis(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    void mysqlOwnOrderIsPersistedWithoutRedisLookup() {
        asUser(1L);
        LocalDateTime updateTime = LocalDateTime.of(2026, 8, 6, 10, 30, 0);
        when(mapper.selectOne(any())).thenReturn(order(1001L, 1L, null, updateTime));

        OrderSubmissionStatusDTO dto = service.getSubmissionStatus(1001L);

        assertThat(dto.getOrderId()).isEqualTo(1001L);
        assertThat(dto.getState()).isEqualTo(OrderSubmissionState.PERSISTED);
        assertThat(dto.getMessage()).isEqualTo("订单已确认落库");
        assertThat(dto.getUpdatedAt()).isEqualTo(toMillis(updateTime));
        verify(repository, never()).find(anyLong());
    }

    @Test
    void mysqlPersistedFallsBackToCreateTimeWhenUpdateTimeMissing() {
        asUser(1L);
        LocalDateTime createTime = LocalDateTime.of(2026, 8, 6, 10, 0, 0);
        when(mapper.selectOne(any())).thenReturn(order(1001L, 1L, createTime, null));

        OrderSubmissionStatusDTO dto = service.getSubmissionStatus(1001L);

        assertThat(dto.getState()).isEqualTo(OrderSubmissionState.PERSISTED);
        assertThat(dto.getUpdatedAt()).isEqualTo(toMillis(createTime));
    }

    @Test
    void redisAcceptedIsReturnedForOwnUser() {
        asUser(1L);
        when(mapper.selectOne(any())).thenReturn(null);
        when(repository.find(1001L)).thenReturn(Optional.of(
                new OrderSubmissionRecord(1001L, OrderSubmissionState.ACCEPTED, 1L, 2L, "订单已受理，等待处理", 123L)));

        OrderSubmissionStatusDTO dto = service.getSubmissionStatus(1001L);

        assertThat(dto.getState()).isEqualTo(OrderSubmissionState.ACCEPTED);
        assertThat(dto.getMessage()).isEqualTo("订单已受理，等待处理");
        assertThat(dto.getUpdatedAt()).isEqualTo(123L);
    }

    @Test
    void redisFailedIsReturnedForOwnUser() {
        asUser(1L);
        when(mapper.selectOne(any())).thenReturn(null);
        when(repository.find(1001L)).thenReturn(Optional.of(
                new OrderSubmissionRecord(1001L, OrderSubmissionState.FAILED, 1L, 2L,
                        "订单处理失败，请稍后重试或联系客服", 456L)));

        OrderSubmissionStatusDTO dto = service.getSubmissionStatus(1001L);

        assertThat(dto.getState()).isEqualTo(OrderSubmissionState.FAILED);
        assertThat(dto.getMessage()).isEqualTo("订单处理失败，请稍后重试或联系客服");
        assertThat(dto.getUpdatedAt()).isEqualTo(456L);
    }

    @Test
    void redisRecordOfOtherUserReturnsUnknown() {
        asUser(1L);
        when(mapper.selectOne(any())).thenReturn(null);
        when(repository.find(1001L)).thenReturn(Optional.of(
                new OrderSubmissionRecord(1001L, OrderSubmissionState.ACCEPTED, 2L, 2L, "订单已受理，等待处理", 123L)));

        OrderSubmissionStatusDTO dto = service.getSubmissionStatus(1001L);

        assertThat(dto.getState()).isEqualTo(OrderSubmissionState.UNKNOWN);
        assertThat(dto.getMessage()).isEqualTo("无法确认订单提交状态");
        assertThat(dto.getUpdatedAt()).isNull();
    }

    @Test
    void missingRedisRecordReturnsUnknown() {
        asUser(1L);
        when(mapper.selectOne(any())).thenReturn(null);
        when(repository.find(1001L)).thenReturn(Optional.empty());

        OrderSubmissionStatusDTO dto = service.getSubmissionStatus(1001L);

        assertThat(dto.getState()).isEqualTo(OrderSubmissionState.UNKNOWN);
    }

    @Test
    void redisExceptionFailsClosedAsServiceUnavailable() {
        asUser(1L);
        when(mapper.selectOne(any())).thenReturn(null);
        when(repository.find(anyLong()))
                .thenThrow(new RedisSystemException("redis down", new RuntimeException("connection refused")));

        assertThatThrownBy(() -> service.getSubmissionStatus(1001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂不可用");
    }

    @Test
    void mysqlExceptionFailsClosedAsServiceUnavailable() {
        asUser(1L);
        when(mapper.selectOne(any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.getSubmissionStatus(1001L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂不可用");
    }

    @Test
    void dtoNeverExposesUserId() {
        assertThat(Arrays.stream(OrderSubmissionStatusDTO.class.getDeclaredFields())
                .map(Field::getName))
                .doesNotContain("userId");
    }

    @Test
    void malformedRedisRecordFailsClosedAsServiceUnavailable() {
        asUser(1L);
        when(mapper.selectOne(any())).thenReturn(null);
        when(repository.find(anyLong()))
                .thenThrow(new IllegalStateException("提交状态记录状态非法：orderId=1001, state=CANCELLED"));

        BusinessException exception = catchThrowableOfType(
                () -> service.getSubmissionStatus(1001L), BusinessException.class);

        assertThat(exception).isNotNull();
        assertThat(exception.getMessage())
                .isEqualTo("订单状态服务暂不可用")
                .doesNotContain("1001", "CANCELLED", "orderId", "提交状态记录");
        assertThat(exception.getCause()).isInstanceOf(IllegalStateException.class);
    }
}
