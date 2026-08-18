package com.linklife.trade.application;

import com.linklife.shared.outbox.OutboxPublishCommand;
import com.linklife.shared.outbox.OutboxPublisher;
import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.mapper.OutboxEventMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

/**
 * MySQL 本地 Outbox 发布实现（017J-B）。
 *
 * <p>在调用方已有 MySQL 本地事务内插入一条 PENDING Outbox 事件：
 * insert 影响行数必须为 1；异常（含 DuplicateKeyException）向上抛出参与整体回滚；
 * 不开启 REQUIRES_NEW、不操作 Redis、不吞异常。</p>
 */
@Component
public class MySqlOutboxPublisher implements OutboxPublisher {

    private static final String STATUS_PENDING = "PENDING";

    @Resource
    private OutboxEventMapper outboxEventMapper;

    @Override
    public void publish(OutboxPublishCommand command) {
        OutboxEvent event = new OutboxEvent();
        event.setEventId(command.eventId());
        event.setBusinessKey(command.businessKey());
        event.setAggregateType(command.aggregateType());
        event.setAggregateId(command.aggregateId());
        event.setEventType(command.eventType());
        event.setEventVersion(command.eventVersion());
        event.setPayload(command.payload());
        event.setStatus(STATUS_PENDING);
        event.setRetryCount(0);
        event.setNextRetryTime(command.now());
        event.setCreatedTime(command.now());
        event.setUpdatedTime(command.now());
        int affected = outboxEventMapper.insert(event);
        if (affected != 1) {
            throw new IllegalStateException(
                    "Outbox 事件写入失败（affected=" + affected + "），事务回滚：eventId="
                            + command.eventId());
        }
    }
}
