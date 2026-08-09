package com.linklife.shared.outbox;

import java.time.LocalDateTime;

/**
 * Outbox 发布命令（不可变，shared 通用端口）。
 *
 * <p>由业务模块在同一 MySQL 本地事务内调用；时间统一秒级；
 * payload 为 JSON 字符串且不得超过 tb_outbox_event.payload 的 varchar(1024) 上限。</p>
 */
public record OutboxPublishCommand(
        String aggregateType,
        long aggregateId,
        String eventType,
        int eventVersion,
        String businessKey,
        String payload,
        String eventId,
        LocalDateTime now) {

    private static final int MAX_PAYLOAD_LENGTH = 1000;

    public OutboxPublishCommand {
        if (aggregateType == null || aggregateType.isBlank() || aggregateType.length() > 32) {
            throw new IllegalArgumentException("aggregateType 必须非空且不超过 32");
        }
        if (aggregateId <= 0) {
            throw new IllegalArgumentException("aggregateId 必须大于 0");
        }
        if (eventType == null || eventType.isBlank() || eventType.length() > 32) {
            throw new IllegalArgumentException("eventType 必须非空且不超过 32");
        }
        if (eventVersion <= 0) {
            throw new IllegalArgumentException("eventVersion 必须大于 0");
        }
        if (businessKey == null || businessKey.isBlank() || businessKey.length() > 96) {
            throw new IllegalArgumentException("businessKey 必须非空且不超过 96");
        }
        if (payload == null || payload.isBlank() || payload.length() > MAX_PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("payload 必须非空且不超过 " + MAX_PAYLOAD_LENGTH);
        }
        if (eventId == null || eventId.isBlank() || eventId.length() > 64) {
            throw new IllegalArgumentException("eventId 必须非空且不超过 64");
        }
        if (now == null || now.getNano() != 0) {
            throw new IllegalArgumentException("now 必须非空且为秒级");
        }
    }
}
