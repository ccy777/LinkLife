package com.linklife.trade.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.lifecycle.outbox.OutboxEventHandler;
import com.linklife.trade.lifecycle.outbox.OutboxEventStatus;
import com.linklife.trade.lifecycle.outbox.OutboxHandleResult;
import com.linklife.trade.lifecycle.outbox.OutboxPollResult;
import com.linklife.trade.lifecycle.outbox.OutboxProperties;
import com.linklife.trade.mapper.OutboxEventMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxPollingService 单元测试：候选查询（过期 PROCESSING 优先、剩余容量、第一页无 count、
 * 稳定排序）、PENDING 领取 CAS 与租约、过期 PROCESSING 回收与 retry_count 条件、SUCCESS/
 * RETRYABLE/FATAL/DEAD 与 lock_token 守卫、handler null/异常、leaseLost、指数退避与统计。
 * 真实 MySQL 多实例竞争、锁等待与租约恢复由后续 017G 隔离集成验证。
 */
class OutboxPollingServiceTest {

    private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

    private OutboxPollingService service;
    private OutboxEventMapper mapper;
    private OutboxProperties properties;
    private OutboxEventHandler handler;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), OutboxEvent.class);
        properties = new OutboxProperties();
        service = new OutboxPollingService();
        mapper = mock(OutboxEventMapper.class);
        handler = mock(OutboxEventHandler.class);
        ReflectionTestUtils.setField(service, "outboxEventMapper", mapper);
        ReflectionTestUtils.setField(service, "outboxProperties", properties);
        ReflectionTestUtils.setField(service, "outboxEventHandler", handler);
        ReflectionTestUtils.setField(service, "clock",
                Clock.fixed(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    private Page<OutboxEvent> pageOf(List<OutboxEvent> records) {
        Page<OutboxEvent> page = new Page<>(1, properties.getBatchSize(), false);
        page.setRecords(records);
        return page;
    }

    private OutboxEvent pendingEvent(long id, int retryCount) {
        OutboxEvent event = new OutboxEvent();
        event.setId(id);
        event.setEventId(UUID.randomUUID().toString());
        event.setBusinessKey("VOUCHER_ORDER:CLOSED:" + id + ":V1");
        event.setPayload("{}");
        event.setStatus(OutboxEventStatus.PENDING.name());
        event.setRetryCount(retryCount);
        event.setNextRetryTime(FIXED_NOW.minusSeconds(1));
        return event;
    }

    private OutboxEvent processingEvent(long id, int retryCount) {
        OutboxEvent event = pendingEvent(id, retryCount);
        event.setStatus(OutboxEventStatus.PROCESSING.name());
        event.setLockToken(UUID.randomUUID().toString());
        event.setLockedUntil(FIXED_NOW.minusSeconds(1));
        return event;
    }

    private void stubNoCandidates() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of()));
    }

    private void stubSinglePendingClaimed() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 1);
    }

    /**
     * 可推进 Clock：仅用于测试，确定性断言 claimNow/finishNow 的读取时点。
     */
    static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advanceSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }

        void advanceNanos(long nanos) {
            instant = instant.plusNanos(nanos);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    void slowHandlerUsesFinishNowForSuccessTimestamps() {
        MutableClock clock = new MutableClock(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        ReflectionTestUtils.setField(service, "clock", clock);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 1);
        when(handler.handle(any(OutboxEvent.class))).thenAnswer(inv -> {
            clock.advanceSeconds(10);
            return OutboxHandleResult.success();
        });

        service.pollDueEvents();

        ArgumentCaptor<OutboxEvent> handlerCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(handler).handle(handlerCaptor.capture());
        OutboxEvent handled = handlerCaptor.getValue();
        assertThat(handled.getProcessingStartedTime()).isEqualTo(FIXED_NOW);
        assertThat(handled.getLockedUntil())
                .isEqualTo(FIXED_NOW.plusSeconds(properties.getLeaseSeconds()));

        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> successUpdate = captor.getAllValues().get(1);
        successUpdate.getSqlSegment();
        assertThat(successUpdate.getParamNameValuePairs().values())
                .contains(FIXED_NOW.plusSeconds(10), FIXED_NOW.plusSeconds(10));
    }

    @Test
    void slowHandlerBackoffStartsFromFinishNow() {
        MutableClock clock = new MutableClock(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        ReflectionTestUtils.setField(service, "clock", clock);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 1);
        when(handler.handle(any(OutboxEvent.class))).thenAnswer(inv -> {
            clock.advanceSeconds(10);
            return OutboxHandleResult.retryable("HANDLER_EXCEPTION");
        });

        service.pollDueEvents();

        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> retryUpdate = captor.getAllValues().get(1);
        retryUpdate.getSqlSegment();
        // finishNow = T0+10s, baseDelay=1s -> next_retry_time = T0+11s（不是 T0+1s）
        assertThat(retryUpdate.getParamNameValuePairs().values())
                .contains(FIXED_NOW.plusSeconds(11));
        assertThat(retryUpdate.getParamNameValuePairs().values())
                .doesNotContain(FIXED_NOW.plusSeconds(1));
    }

    @Test
    void multipleCandidatesGetIndependentClaimTimes() {
        MutableClock clock = new MutableClock(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        ReflectionTestUtils.setField(service, "clock", clock);
        properties.setBatchSize(2);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()),
                        pageOf(List.of(pendingEvent(1001L, 0), pendingEvent(1002L, 0))),
                        pageOf(List.of()), pageOf(List.of()));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(handler.handle(any(OutboxEvent.class))).thenAnswer(inv -> {
            clock.advanceSeconds(70);
            return OutboxHandleResult.success();
        });

        service.pollDueEvents();

        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        // claim1, result1, claim2, result2
        verify(mapper, times(4)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> firstClaim = captor.getAllValues().get(0);
        LambdaUpdateWrapper<OutboxEvent> secondClaim = captor.getAllValues().get(2);
        firstClaim.getSqlSegment();
        secondClaim.getSqlSegment();
        assertThat(firstClaim.getParamNameValuePairs().values()).contains(FIXED_NOW);
        assertThat(secondClaim.getParamNameValuePairs().values())
                .contains(FIXED_NOW.plusSeconds(70));
        // 第二个候选刚领取即获得未过期租约：lockedUntil = T0+70+lease
        assertThat(secondClaim.getParamNameValuePairs().values())
                .contains(FIXED_NOW.plusSeconds(70 + properties.getLeaseSeconds()));
    }

    @Test
    void expiredProcessingReclaimUsesFreshClaimTime() {
        MutableClock clock = new MutableClock(FIXED_NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        ReflectionTestUtils.setField(service, "clock", clock);
        AtomicInteger queryCount = new AtomicInteger();
        when(mapper.selectPage(any(Page.class), any())).thenAnswer(inv -> {
            boolean firstQuery = queryCount.getAndIncrement() == 0;
            if (firstQuery) {
                clock.advanceSeconds(5);
            }
            return firstQuery ? pageOf(List.of(processingEvent(1001L, 1))) : pageOf(List.of());
        });
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 1);
        when(handler.handle(any(OutboxEvent.class))).thenReturn(OutboxHandleResult.success());

        service.pollDueEvents();

        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> reclaim = captor.getAllValues().get(0);
        String segment = reclaim.getSqlSegment();
        assertThat(segment).contains("locked_until").contains("retry_count");
        reclaim.getSqlSegment();
        // claimNow = T0+5s（不是 scanNow T0）
        assertThat(reclaim.getParamNameValuePairs().values())
                .contains(FIXED_NOW.plusSeconds(5), FIXED_NOW.plusSeconds(5 + properties.getLeaseSeconds()));
        ArgumentCaptor<OutboxEvent> handlerCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(handler).handle(handlerCaptor.capture());
        assertThat(handlerCaptor.getValue().getProcessingStartedTime())
                .isEqualTo(FIXED_NOW.plusSeconds(5));
    }

    @Test
    void expiredProcessingQueriedFirstThenPendingWithRemainingCapacity() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(processingEvent(1001L, 1))), pageOf(List.of()));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.scanned()).isEqualTo(1);
        ArgumentCaptor<LambdaQueryWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper, times(2)).selectPage(any(Page.class), captor.capture());
        LambdaQueryWrapper<OutboxEvent> processingQuery = captor.getAllValues().get(0);
        LambdaQueryWrapper<OutboxEvent> pendingQuery = captor.getAllValues().get(1);
        assertThat(processingQuery.getSqlSegment()).contains("status").contains("locked_until");
        assertThat(pendingQuery.getSqlSegment()).contains("status").contains("next_retry_time");
        processingQuery.getSqlSegment();
        pendingQuery.getSqlSegment();
        assertThat(processingQuery.getParamNameValuePairs().values()).contains("PROCESSING");
        assertThat(pendingQuery.getParamNameValuePairs().values()).contains("PENDING");
    }

    @Test
    void processingQueryConditionsAndOrdering() {
        stubNoCandidates();

        service.pollDueEvents();

        ArgumentCaptor<LambdaQueryWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper, times(2)).selectPage(any(Page.class), captor.capture());
        String segment = captor.getAllValues().get(0).getSqlSegment();
        assertThat(segment).contains("status").contains("locked_until");
        assertThat(segment).contains("locked_until ASC").contains("id ASC");
        assertThat(segment.indexOf("locked_until ASC")).isLessThan(segment.indexOf("id ASC"));
    }

    @Test
    void pendingQueryConditionsAndOrdering() {
        stubNoCandidates();

        service.pollDueEvents();

        ArgumentCaptor<LambdaQueryWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper, times(2)).selectPage(any(Page.class), captor.capture());
        String segment = captor.getAllValues().get(1).getSqlSegment();
        assertThat(segment).contains("status").contains("next_retry_time");
        assertThat(segment).contains("next_retry_time ASC").contains("id ASC");
        assertThat(segment.indexOf("next_retry_time ASC")).isLessThan(segment.indexOf("id ASC"));
    }

    @Test
    void bothQueriesAreFirstPageNoCount() {
        stubNoCandidates();

        service.pollDueEvents();

        ArgumentCaptor<Page<OutboxEvent>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(mapper, times(2)).selectPage(pageCaptor.capture(), any());
        for (Page<OutboxEvent> page : pageCaptor.getAllValues()) {
            assertThat(page.getCurrent()).isEqualTo(1);
            assertThat(page.searchCount()).isFalse();
            assertThat(page.getSize()).isEqualTo(properties.getBatchSize());
        }
    }

    @Test
    void remainingCapacityIsAccurate() {
        properties.setBatchSize(5);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(processingEvent(1001L, 0), processingEvent(1002L, 0),
                                processingEvent(1003L, 0))),
                        pageOf(List.of(pendingEvent(2001L, 0), pendingEvent(2002L, 0))),
                        pageOf(List.of()), pageOf(List.of()));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(handler.handle(any(OutboxEvent.class))).thenReturn(OutboxHandleResult.success());

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.scanned()).isEqualTo(5);
        ArgumentCaptor<Page<OutboxEvent>> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(mapper, times(4)).selectPage(pageCaptor.capture(), any());
        assertThat(pageCaptor.getAllValues().get(0).getSize()).isEqualTo(5);
        assertThat(pageCaptor.getAllValues().get(1).getSize()).isEqualTo(2);
    }

    @Test
    void noOffsetPaginationAndNoFullTableJavaFilter() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/application/OutboxPollingService.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("OFFSET")
                .doesNotContain("offset(")
                .doesNotContain("selectList(new LambdaQueryWrapper")
                .doesNotContain(".last(");
    }

    @Test
    void candidateSelectsOnlyNeededColumns() {
        stubNoCandidates();

        service.pollDueEvents();

        ArgumentCaptor<LambdaQueryWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper, times(2)).selectPage(any(Page.class), captor.capture());
        String select = captor.getAllValues().get(0).getSqlSelect();
        assertThat(select).contains("id").contains("event_id").contains("business_key")
                .contains("payload").contains("status").contains("retry_count")
                .contains("lock_token").contains("locked_until").contains("next_retry_time");
    }

    @Test
    void pendingClaimCasConditionsAndLeaseFields() {
        stubSinglePendingClaimed();
        when(handler.handle(any(OutboxEvent.class))).thenReturn(OutboxHandleResult.success());

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);

        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> claim = captor.getAllValues().get(0);
        String segment = claim.getSqlSegment();
        assertThat(segment).contains("id").contains("status").contains("next_retry_time");
        claim.getSqlSegment();
        assertThat(claim.getParamNameValuePairs().values()).contains(1001L);
        String set = claim.getSqlSet();
        assertThat(set).contains("status").contains("lock_token").contains("locked_until")
                .contains("processing_started_time").contains("updated_time");
        claim.getSqlSegment();
        assertThat(claim.getParamNameValuePairs().values()).contains(FIXED_NOW);

        ArgumentCaptor<OutboxEvent> handlerCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(handler).handle(handlerCaptor.capture());
        OutboxEvent handled = handlerCaptor.getValue();
        assertThat(UUID.fromString(handled.getLockToken())).isNotNull();
        assertThat(handled.getLockedUntil()).isEqualTo(FIXED_NOW.plusSeconds(properties.getLeaseSeconds()));
        assertThat(handled.getProcessingStartedTime()).isEqualTo(FIXED_NOW);
        assertThat(handled.getStatus()).isEqualTo(OutboxEventStatus.PROCESSING.name());
        assertThat(handled.getRetryCount()).isZero();
    }

    @Test
    void claimAffectedZeroSkippedWithoutHandler() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.claimed()).isZero();
        verify(handler, never()).handle(any(OutboxEvent.class));
    }

    @Test
    void claimAffectedNegativeOrGreaterThanOneFailsClosed() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(-1);

        assertThatThrownBy(() -> service.pollDueEvents())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed");

        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(2);
        assertThatThrownBy(() -> service.pollDueEvents())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-closed");
    }

    @Test
    void expiredProcessingReclaimIncrementsRetryWithOldRetryCondition() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(processingEvent(1001L, 2))), pageOf(List.of()));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 1);
        when(handler.handle(any(OutboxEvent.class))).thenReturn(OutboxHandleResult.success());

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.claimed()).isEqualTo(1);
        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> reclaim = captor.getAllValues().get(0);
        String segment = reclaim.getSqlSegment();
        assertThat(segment).contains("locked_until").contains("retry_count");
        reclaim.getSqlSegment();
        assertThat(reclaim.getParamNameValuePairs().values()).contains(2);
        assertThat(reclaim.getSqlSet()).contains("retry_count");

        ArgumentCaptor<OutboxEvent> handlerCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(handler).handle(handlerCaptor.capture());
        assertThat(handlerCaptor.getValue().getRetryCount()).isEqualTo(3);
    }

    @Test
    void expiredProcessingReachesMaxDeadWithoutHandler() {
        properties.setMaxRetries(3);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of(processingEvent(1001L, 2))), pageOf(List.of()));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.dead()).isEqualTo(1);
        assertThat(result.claimed()).isZero();
        verify(handler, never()).handle(any(OutboxEvent.class));
        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> deadUpdate = captor.getValue();
        assertThat(deadUpdate.getSqlSet()).contains("status").contains("last_error_code");
        deadUpdate.getSqlSegment();
        assertThat(deadUpdate.getParamNameValuePairs().values()).contains("DEAD", "LEASE_EXPIRED");
    }

    @Test
    void successUpdateIsTokenGuarded() {
        stubSinglePendingClaimed();
        when(handler.handle(any(OutboxEvent.class))).thenReturn(OutboxHandleResult.success());

        service.pollDueEvents();

        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> result = captor.getAllValues().get(1);
        String segment = result.getSqlSegment();
        assertThat(segment).contains("status").contains("lock_token");
        result.getSqlSegment();
        assertThat(result.getParamNameValuePairs().values())
                .contains(1001L, OutboxEventStatus.PROCESSING.name());
        String set = result.getSqlSet();
        assertThat(set).contains("status").contains("completed_time").contains("updated_time")
                .contains("last_error_code").contains("lock_token").contains("locked_until");
        result.getSqlSegment();
        assertThat(result.getParamNameValuePairs().values()).contains("SUCCESS");
    }

    @Test
    void retryableGoesPendingWithBackoff() {
        stubSinglePendingClaimed();
        when(handler.handle(any(OutboxEvent.class)))
                .thenReturn(OutboxHandleResult.retryable("HANDLER_EXCEPTION"));

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.retried()).isEqualTo(1);
        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> update = captor.getAllValues().get(1);
        String set = update.getSqlSet();
        assertThat(set).contains("status").contains("retry_count").contains("next_retry_time")
                .contains("last_error_code");
        update.getSqlSegment();
        // retryCount=1 -> baseDelay=1000ms -> next_retry_time = FIXED_NOW + 1s
        assertThat(update.getParamNameValuePairs().values())
                .contains("PENDING", "HANDLER_EXCEPTION", 1, FIXED_NOW.plusSeconds(1));
    }

    @Test
    void retryableReachesMaxGoesDead() {
        properties.setMaxRetries(2);
        OutboxEvent event = pendingEvent(1001L, 1);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(event)));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 1);
        when(handler.handle(any(OutboxEvent.class)))
                .thenReturn(OutboxHandleResult.retryable("HANDLER_EXCEPTION"));

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.dead()).isEqualTo(1);
        assertThat(result.retried()).isZero();
        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> deadUpdate = captor.getAllValues().get(1);
        assertThat(deadUpdate.getSqlSet()).contains("status").contains("last_error_code");
        deadUpdate.getSqlSegment();
        assertThat(deadUpdate.getParamNameValuePairs().values()).contains("DEAD", "HANDLER_EXCEPTION");
    }

    @Test
    void fatalGoesDeadImmediately() {
        stubSinglePendingClaimed();
        when(handler.handle(any(OutboxEvent.class)))
                .thenReturn(OutboxHandleResult.fatal("COMPENSATION_FAILED"));

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.dead()).isEqualTo(1);
        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> deadUpdate = captor.getAllValues().get(1);
        assertThat(deadUpdate.getSqlSet()).contains("status").contains("last_error_code");
        deadUpdate.getSqlSegment();
        assertThat(deadUpdate.getParamNameValuePairs().values())
                .contains("DEAD", "COMPENSATION_FAILED");
    }

    @Test
    void handlerNullResultMapsToRetryable() {
        stubSinglePendingClaimed();
        when(handler.handle(any(OutboxEvent.class))).thenReturn(null);

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.retried()).isEqualTo(1);
        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> retryUpdate = captor.getAllValues().get(1);
        retryUpdate.getSqlSegment();
        assertThat(retryUpdate.getParamNameValuePairs().values()).contains("HANDLER_NULL_RESULT");
    }

    @Test
    void handlerRuntimeExceptionMapsToRetryableWithoutSecrets() {
        stubSinglePendingClaimed();
        when(handler.handle(any(OutboxEvent.class)))
                .thenThrow(new RuntimeException("secret sql: select * from users"));

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.retried()).isEqualTo(1);
        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        String set = captor.getAllValues().get(1).getSqlSet();
        LambdaUpdateWrapper<OutboxEvent> retryUpdate = captor.getAllValues().get(1);
        retryUpdate.getSqlSegment();
        assertThat(retryUpdate.getParamNameValuePairs().values()).contains("HANDLER_EXCEPTION");
        assertThat(retryUpdate.getParamNameValuePairs().values())
                .doesNotContain("secret sql: select * from users");
    }

    @Test
    void successUpdateAffectedZeroMarksLeaseLostWithoutOverwrite() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 0);
        when(handler.handle(any(OutboxEvent.class))).thenReturn(OutboxHandleResult.success());

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.leaseLost()).isEqualTo(1);
        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.succeeded()).isZero();
    }

    @Test
    void retryUpdateAffectedZeroMarksLeaseLost() {
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 0);
        when(handler.handle(any(OutboxEvent.class)))
                .thenReturn(OutboxHandleResult.retryable("HANDLER_EXCEPTION"));

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.leaseLost()).isEqualTo(1);
        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.retried()).isZero();
    }

    @Test
    void claimAndQueryTimesAreTruncatedToSeconds() {
        Instant t0 = LocalDateTime.of(2026, 8, 6, 10, 0, 0).toInstant(ZoneOffset.UTC);
        MutableClock clock = new MutableClock(t0.plusNanos(987_654_321L), ZoneOffset.UTC);
        ReflectionTestUtils.setField(service, "clock", clock);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 1);
        when(handler.handle(any(OutboxEvent.class))).thenReturn(OutboxHandleResult.success());

        service.pollDueEvents();

        ArgumentCaptor<LambdaQueryWrapper<OutboxEvent>> queryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(mapper, times(2)).selectPage(any(Page.class), queryCaptor.capture());
        LambdaQueryWrapper<OutboxEvent> pendingQuery = queryCaptor.getAllValues().get(1);
        pendingQuery.getSqlSegment();
        assertThat(pendingQuery.getParamNameValuePairs().values())
                .anySatisfy(v -> {
                    assertThat(v).isEqualTo(LocalDateTime.of(2026, 8, 6, 10, 0, 0));
                    assertThat(((LocalDateTime) v).getNano()).isZero();
                });

        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> claim = captor.getAllValues().get(0);
        claim.getSqlSegment();
        for (Object value : claim.getParamNameValuePairs().values()) {
            if (value instanceof LocalDateTime time) {
                assertThat(time.getNano()).isZero();
            }
        }
        assertThat(claim.getParamNameValuePairs().values())
                .contains(LocalDateTime.of(2026, 8, 6, 10, 0, 0),
                        LocalDateTime.of(2026, 8, 6, 10, 1, 0));
    }

    @Test
    void slowHandlerFinishIsTruncatedToSeconds() {
        Instant t0 = LocalDateTime.of(2026, 8, 6, 10, 0, 0).toInstant(ZoneOffset.UTC);
        MutableClock clock = new MutableClock(t0.plusNanos(987_654_321L), ZoneOffset.UTC);
        ReflectionTestUtils.setField(service, "clock", clock);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 1);
        when(handler.handle(any(OutboxEvent.class))).thenAnswer(inv -> {
            clock.advanceNanos(10_500_000_000L);
            return OutboxHandleResult.success();
        });

        service.pollDueEvents();

        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> successUpdate = captor.getAllValues().get(1);
        successUpdate.getSqlSegment();
        LocalDateTime expectedFinish = LocalDateTime.of(2026, 8, 6, 10, 0, 11);
        assertThat(successUpdate.getParamNameValuePairs().values())
                .contains(expectedFinish, expectedFinish);
        for (Object value : successUpdate.getParamNameValuePairs().values()) {
            if (value instanceof LocalDateTime time) {
                assertThat(time.getNano()).isZero();
            }
        }
    }

    @Test
    void retryableNextRetryIsTruncatedFinishPlusWholeSecondBackoff() {
        Instant t0 = LocalDateTime.of(2026, 8, 6, 10, 0, 0).toInstant(ZoneOffset.UTC);
        MutableClock clock = new MutableClock(t0.plusNanos(987_654_321L), ZoneOffset.UTC);
        ReflectionTestUtils.setField(service, "clock", clock);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1, 1);
        when(handler.handle(any(OutboxEvent.class))).thenAnswer(inv -> {
            clock.advanceNanos(10_500_000_000L);
            return OutboxHandleResult.retryable("HANDLER_EXCEPTION");
        });

        service.pollDueEvents();

        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        LambdaUpdateWrapper<OutboxEvent> retryUpdate = captor.getAllValues().get(1);
        retryUpdate.getSqlSegment();
        // finishNow = 10:00:11（截断到秒），backoff=1s -> nextRetryTime = 10:00:12
        LocalDateTime expectedNextRetry = LocalDateTime.of(2026, 8, 6, 10, 0, 12);
        assertThat(retryUpdate.getParamNameValuePairs().values()).contains(expectedNextRetry);
        for (Object value : retryUpdate.getParamNameValuePairs().values()) {
            if (value instanceof LocalDateTime time) {
                assertThat(time.getNano()).isZero();
            }
        }
    }

    @Test
    void resultUpdateAlwaysGuardedByCurrentToken() {
        stubSinglePendingClaimed();
        when(handler.handle(any(OutboxEvent.class))).thenReturn(OutboxHandleResult.success());

        service.pollDueEvents();

        ArgumentCaptor<LambdaUpdateWrapper<OutboxEvent>> captor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper, times(2)).update(isNull(), captor.capture());
        ArgumentCaptor<OutboxEvent> handlerCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(handler).handle(handlerCaptor.capture());
        LambdaUpdateWrapper<OutboxEvent> resultUpdate = captor.getAllValues().get(1);
        resultUpdate.getSqlSegment();
        assertThat(resultUpdate.getParamNameValuePairs().values())
                .contains(handlerCaptor.getValue().getLockToken());
    }

    @Test
    void emptyRoundReturnsZeroCounts() {
        stubNoCandidates();

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.batches()).isZero();
        assertThat(result.scanned()).isZero();
        assertThat(result.claimed()).isZero();
        assertThat(result.limitReached()).isFalse();
        verify(handler, never()).handle(any(OutboxEvent.class));
    }

    @Test
    void maxBatchesPerRunSetsLimitReached() {
        properties.setBatchSize(1);
        properties.setMaxBatchesPerRun(2);
        when(mapper.selectPage(any(Page.class), any()))
                .thenReturn(pageOf(List.of()), pageOf(List.of(pendingEvent(1001L, 0))),
                        pageOf(List.of()), pageOf(List.of(pendingEvent(1002L, 0))),
                        pageOf(List.of()), pageOf(List.of(pendingEvent(1003L, 0))));
        when(mapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(handler.handle(any(OutboxEvent.class))).thenReturn(OutboxHandleResult.success());

        OutboxPollResult result = service.pollDueEvents();

        assertThat(result.batches()).isEqualTo(2);
        assertThat(result.limitReached()).isTrue();
        assertThat(result.succeeded()).isEqualTo(2);
    }

    @Test
    void backoffFormulaIsOverflowSafeAndCapped() {
        properties.setRetryBaseDelayMs(1000L);
        properties.setRetryMaxDelayMs(60000L);

        assertThat(service.computeBackoffMillis(1)).isEqualTo(1000L);
        assertThat(service.computeBackoffMillis(2)).isEqualTo(2000L);
        assertThat(service.computeBackoffMillis(3)).isEqualTo(4000L);
        assertThat(service.computeBackoffMillis(6)).isEqualTo(32000L);
        assertThat(service.computeBackoffMillis(7)).isEqualTo(60000L);
        assertThat(service.computeBackoffMillis(100)).isEqualTo(60000L);
    }
}
