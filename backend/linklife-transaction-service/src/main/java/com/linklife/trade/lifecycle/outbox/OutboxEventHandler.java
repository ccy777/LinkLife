package com.linklife.trade.lifecycle.outbox;

import com.linklife.trade.entity.OutboxEvent;

/**
 * Outbox 事件处理器接口。
 *
 * <p>轮询框架只依赖本接口。017F 起提供唯一生产实现 {@code OrderClosedOutboxEventHandler}
 * （ORDER_CLOSED Redis 库存幂等补偿，仅 enabled=true 时创建）。处理器收到的 {@link OutboxEvent}
 * 携带本次领取上下文
 * （status=PROCESSING、lockToken、lockedUntil、processingStartedTime、retryCount）。</p>
 */
public interface OutboxEventHandler {

    /**
     * 处理一条已领取的 Outbox 事件。
     *
     * @param event 携带领取上下文的事件对象
     * @return 处理结果；返回 null 由轮询框架按可重试失败（HANDLER_NULL_RESULT）处理
     */
    OutboxHandleResult handle(OutboxEvent event);
}
