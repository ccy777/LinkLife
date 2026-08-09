package com.linklife.trade.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderTimeoutCloseService 单元测试（017D 接入统一关闭事务内核后）：
 * 保留候选查询/第一页无 count/稳定排序/批次上限/统计语义；
 * 每条候选构造精确 TIMEOUT_CLOSE 命令并委托内核，结果映射与异常原样传播。
 * 真实 MySQL 事务、行锁与并发可见性由后续 017G 隔离集成验证。
 */
class OrderTimeoutCloseServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 6, 10, 0, 0);
    private static final LocalDateTime FIXED_CUTOFF = FIXED_NOW.minusMinutes(15);

    private OrderTimeoutCloseService service;
    private VoucherOrderMapper mapper;
    private OrderTimeoutProperties properties;
    private OrderCloseTransactionService closeService;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), VoucherOrder.class);
        properties = new OrderTimeoutProperties();
        service = new OrderTimeoutCloseService();
        mapper = mock(VoucherOrderMapper.class);
        closeService = mock(OrderCloseTransactionService.class);
        ReflectionTestUtils.setField(service, "voucherOrderMapper", mapper);
        ReflectionTestUtils.setField(service, "orderTimeoutProperties", properties);
        ReflectionTestUtils.setField(service, "orderCloseTransactionService", closeService);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private Page<VoucherOrder> pageOf(List<VoucherOrder> records) {
        Page<VoucherOrder> page = new Page<>(1, properties.getBatchSize(), false);
        page.setRecords(records);
        return page;
    }

    private VoucherOrder candidate(long id, LocalDateTime createTime) {
        VoucherOrder order = new VoucherOrder();
        order.setId(id);
        order.setCreateTime(createTime);
        return order;
    }

    private List<VoucherOrder> fullBatch(int size) {
        List<VoucherOrder> records = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            records.add(candidate(1000L + i, FIXED_CUTOFF.minusMinutes(1)));
        }
        return records;
    }

    private void stubClosed() {
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.CLOSED);
    }

    @Test
    void candidateQueryConditionsAndStableOrdering() {
        when(mapper.selectPage(any(Page.class), any())).thenReturn(pageOf(List.of()));

        service.closeExpiredOrders();

        ArgumentCaptor<LambdaQueryWrapper<VoucherOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(any(Page.class), captor.capture());
        String segment = captor.getValue().getSqlSegment();
        assertThat(segment).contains("status").contains("create_time");
        assertThat(segment).contains("create_time ASC").contains("id ASC");
        assertThat(segment.indexOf("create_time ASC")).isLessThan(segment.indexOf("id ASC"));
    }

    @Test
    void firstPageNoCountAndMinimalFields() {
        when(mapper.selectPage(any(Page.class), any())).thenReturn(pageOf(List.of()));

        service.closeExpiredOrders();

        ArgumentCaptor<Page<VoucherOrder>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        ArgumentCaptor<LambdaQueryWrapper<VoucherOrder>> wrapperCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(properties.getBatchSize());
        assertThat(pageCaptor.getValue().searchCount()).isFalse();
        String select = wrapperCaptor.getValue().getSqlSelect();
        assertThat(select).contains("id").contains("create_time");
        assertThat(select).doesNotContain("user_id").doesNotContain("voucher_id").doesNotContain("status");
    }

    @Test
    void emptyBatchStopsImmediately() {
        when(mapper.selectPage(any(Page.class), any())).thenReturn(pageOf(List.of()));

        OrderTimeoutCloseResult result = service.closeExpiredOrders();

        assertThat(result.batches()).isZero();
        assertThat(result.scanned()).isZero();
        assertThat(result.limitReached()).isFalse();
        verify(mapper, times(1)).selectPage(any(Page.class), any());
    }

    @Test
    void batchBelowSizeStops() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(candidate(1001L, FIXED_CUTOFF.minusMinutes(1)))));
        stubClosed();

        OrderTimeoutCloseResult result = service.closeExpiredOrders();

        assertThat(result.batches()).isEqualTo(1);
        assertThat(result.scanned()).isEqualTo(1);
        verify(mapper, times(1)).selectPage(any(Page.class), any());
    }

    @Test
    void fullBatchReQueriesFirstPage() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(fullBatch(properties.getBatchSize())),
                        pageOf(fullBatch(properties.getBatchSize() / 2)));
        stubClosed();

        OrderTimeoutCloseResult result = service.closeExpiredOrders();

        assertThat(result.batches()).isEqualTo(2);
        assertThat(result.scanned()).isEqualTo(properties.getBatchSize() + properties.getBatchSize() / 2);
        ArgumentCaptor<Page<VoucherOrder>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(mapper, times(2)).selectPage(pageCaptor.capture(), any());
        for (Page<VoucherOrder> page : pageCaptor.getAllValues()) {
            assertThat(page.getCurrent()).isEqualTo(1);
        }
    }

    @Test
    void maxBatchesPerRunSetsLimitReached() {
        properties.setMaxBatchesPerRun(2);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(fullBatch(properties.getBatchSize())),
                        pageOf(fullBatch(properties.getBatchSize())));
        stubClosed();

        OrderTimeoutCloseResult result = service.closeExpiredOrders();

        assertThat(result.batches()).isEqualTo(2);
        assertThat(result.scanned()).isEqualTo(properties.getBatchSize() * 2L);
        assertThat(result.limitReached()).isTrue();
        verify(mapper, times(2)).selectPage(any(Page.class), any());
    }

    @Test
    void queryExceptionIsNotSwallowed() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatThrownBy(() -> service.closeExpiredOrders())
                .isInstanceOf(DataAccessResourceFailureException.class)
                .hasMessageContaining("db down");
    }

    @Test
    void eachCandidateBuildsExactTimeoutCommand() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(candidate(1001L, FIXED_CUTOFF.minusMinutes(1)))));
        stubClosed();

        service.closeExpiredOrders();

        ArgumentCaptor<OrderCloseCommand> captor = ArgumentCaptor.forClass(OrderCloseCommand.class);
        verify(closeService).close(captor.capture());
        OrderCloseCommand command = captor.getValue();
        assertThat(command.orderId()).isEqualTo(1001L);
        assertThat(command.userId()).isNull();
        assertThat(command.triggerType()).isEqualTo(OrderCloseTriggerType.TIMEOUT_CLOSE);
        assertThat(command.cutoff()).isEqualTo(FIXED_CUTOFF);
        assertThat(command.reasonCode()).isEqualTo(OrderCloseReasonCode.TIMEOUT_EXPIRED);
        assertThat(command.now()).isEqualTo(FIXED_NOW);
    }

    @Test
    void multipleCandidatesShareSameNowAndCutoff() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(candidate(1001L, FIXED_CUTOFF.minusMinutes(1)),
                        candidate(1002L, FIXED_CUTOFF.minusMinutes(2)))));
        stubClosed();

        service.closeExpiredOrders();

        ArgumentCaptor<OrderCloseCommand> captor = ArgumentCaptor.forClass(OrderCloseCommand.class);
        verify(closeService, times(2)).close(captor.capture());
        List<OrderCloseCommand> commands = captor.getAllValues();
        assertThat(commands).hasSize(2);
        assertThat(commands.get(0).now()).isEqualTo(commands.get(1).now());
        assertThat(commands.get(0).cutoff()).isEqualTo(commands.get(1).cutoff());
        assertThat(commands.get(0).now()).isEqualTo(FIXED_NOW);
        assertThat(commands.get(0).cutoff()).isEqualTo(FIXED_CUTOFF);
    }

    @Test
    void closedCountsClosed() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(candidate(1001L, FIXED_CUTOFF.minusMinutes(1)))));
        stubClosed();

        OrderTimeoutCloseResult result = service.closeExpiredOrders();

        assertThat(result.closed()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
    }

    @Test
    void alreadyCanceledNotFoundNotClosableCountSkipped() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(candidate(1001L, FIXED_CUTOFF.minusMinutes(1)),
                        candidate(1002L, FIXED_CUTOFF.minusMinutes(2)),
                        candidate(1003L, FIXED_CUTOFF.minusMinutes(3)))));
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.ALREADY_CANCELED,
                        OrderCloseResult.NOT_FOUND,
                        OrderCloseResult.NOT_CLOSABLE);

        OrderTimeoutCloseResult result = service.closeExpiredOrders();

        assertThat(result.closed()).isZero();
        assertThat(result.skipped()).isEqualTo(3);
        assertThat(result.scanned()).isEqualTo(3);
    }

    @Test
    void dataInconsistentFailsClosed() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(candidate(1001L, FIXED_CUTOFF.minusMinutes(1)))));
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.DATA_INCONSISTENT);

        assertThatThrownBy(() -> service.closeExpiredOrders())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    void kernelExceptionPropagatesAndStopsRound() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(candidate(1001L, FIXED_CUTOFF.minusMinutes(1)))));
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.closeExpiredOrders())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
        verify(closeService, times(1)).close(any(OrderCloseCommand.class));
    }

    @Test
    void scannedStatisticIsPreserved() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(candidate(1001L, FIXED_CUTOFF.minusMinutes(1)),
                        candidate(1002L, FIXED_CUTOFF.minusMinutes(2)))));
        when(closeService.close(any(OrderCloseCommand.class)))
                .thenReturn(OrderCloseResult.CLOSED, OrderCloseResult.NOT_CLOSABLE);

        OrderTimeoutCloseResult result = service.closeExpiredOrders();

        assertThat(result.scanned()).isEqualTo(2);
        assertThat(result.closed()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
    }

    @Test
    void serviceNoLongerDirectlyUpdatesOrders() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/application/OrderTimeoutCloseService.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("LambdaUpdateWrapper")
                .doesNotContain("voucherOrderMapper.update")
                .doesNotContain("affected");
    }

    @Test
    void noDistributedLockRedisOrSrem() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/application/OrderTimeoutCloseService.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("RedissonClient")
                .doesNotContain("RLock")
                .doesNotContain("synchronized")
                .doesNotContain("StringRedisTemplate");
    }

    @Test
    void resultIsImmutableAndCountsAreCorrect() {
        assertThat(OrderTimeoutCloseResult.class.isRecord()).isTrue();

        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(candidate(1001L, FIXED_CUTOFF.minusMinutes(1)))));
        stubClosed();

        OrderTimeoutCloseResult result = service.closeExpiredOrders();

        assertThat(result.cutoff()).isEqualTo(FIXED_CUTOFF);
        assertThat(result.batches()).isEqualTo(1);
        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.closed()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
        assertThat(result.limitReached()).isFalse();
    }
}
