package com.linklife.trade.application;

import com.linklife.shared.outbox.OutboxPublishCommand;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.mapper.OutboxEventMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MySqlOutboxPublisher 单元测试：command 校验、PENDING 字段、秒级时间、insert=1、
 * insert=0/异常 fail-closed、不操作 Redis。
 */
class MySqlOutboxPublisherTest {

    private MySqlOutboxPublisher publisher;
    private OutboxEventMapper outboxEventMapper;

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

    @BeforeEach
    void setUp() {
        publisher = new MySqlOutboxPublisher();
        outboxEventMapper = mock(OutboxEventMapper.class);
        ReflectionTestUtils.setField(publisher, "outboxEventMapper", outboxEventMapper);
    }

    private OutboxPublishCommand command() {
        return new OutboxPublishCommand(
                "SECKILL_VOUCHER", 100L, "SECKILL_VOUCHER_CREATED", 1,
                "SECKILL_VOUCHER:CREATED:100:V1", "{\"eventId\":\"e1\"}", "e1", NOW);
    }

    @Test
    void invalidCommandsAreRejected() {
        assertThatThrownBy(() -> new OutboxPublishCommand(
                " ", 100L, "T", 1, "K", "{}", "e", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxPublishCommand(
                "T", 0L, "T", 1, "K", "{}", "e", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxPublishCommand(
                "T", 100L, "T", 1, "K", "", "e", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OutboxPublishCommand(
                "T", 100L, "T", 1, "K", "{}", "e", LocalDateTime.of(2026, 8, 6, 10, 0, 0, 123)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publishAssemblesPendingEventWithSecondPrecision() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(1);

        publisher.publish(command());

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insert(captor.capture());
        OutboxEvent event = captor.getValue();
        assertThat(event.getStatus()).isEqualTo("PENDING");
        assertThat(event.getEventId()).isEqualTo("e1");
        assertThat(event.getBusinessKey()).isEqualTo("SECKILL_VOUCHER:CREATED:100:V1");
        assertThat(event.getAggregateType()).isEqualTo("SECKILL_VOUCHER");
        assertThat(event.getAggregateId()).isEqualTo(100L);
        assertThat(event.getEventType()).isEqualTo("SECKILL_VOUCHER_CREATED");
        assertThat(event.getEventVersion()).isEqualTo(1);
        assertThat(event.getPayload()).isEqualTo("{\"eventId\":\"e1\"}");
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getNextRetryTime()).isEqualTo(NOW);
        assertThat(event.getCreatedTime()).isEqualTo(NOW);
        assertThat(event.getUpdatedTime()).isEqualTo(NOW);
        assertThat(event.getLockToken()).isNull();
        assertThat(event.getLockedUntil()).isNull();
    }

    @Test
    void insertZeroAffectedFailsClosed() {
        when(outboxEventMapper.insert(any(OutboxEvent.class))).thenReturn(0);

        assertThatThrownBy(() -> publisher.publish(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("affected=0");
    }

    @Test
    void insertExceptionPropagates() {
        when(outboxEventMapper.insert(any(OutboxEvent.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> publisher.publish(command()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }

    @Test
    void publisherDoesNotDependOnRedis() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/com/linklife/trade/application/MySqlOutboxPublisher.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(source).doesNotContain("StringRedisTemplate").doesNotContain("RedisTemplate");
    }
}
