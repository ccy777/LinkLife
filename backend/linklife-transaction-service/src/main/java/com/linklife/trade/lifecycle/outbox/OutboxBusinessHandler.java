package com.linklife.trade.lifecycle.outbox;

import com.linklife.trade.entity.OutboxEvent;

/**
 * Outbox 业务 Handler（017J-B 多事件路由的内部端口）。
 *
 * <p>Router 按 (eventType, eventVersion) 精确路由到业务 Handler；
 * 业务 Handler 不直接更新 Outbox，不捕获并吞掉 RuntimeException
 * （由轮询框架映射为可重试 HANDLER_EXCEPTION）。</p>
 */
public interface OutboxBusinessHandler {

    /**
     * 本 Handler 支持的事件类型。
     */
    String eventType();

    /**
     * 本 Handler 支持的事件版本。
     */
    int eventVersion();

    /**
     * 处理一条已领取的 Outbox 事件。
     *
     * @param event 携带领取上下文的事件对象
     * @return 处理结果
     */
    OutboxHandleResult handle(OutboxEvent event);
}
