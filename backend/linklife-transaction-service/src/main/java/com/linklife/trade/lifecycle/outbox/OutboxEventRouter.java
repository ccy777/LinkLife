package com.linklife.trade.lifecycle.outbox;

import com.linklife.trade.entity.OutboxEvent;
import com.linklife.trade.lifecycle.timeout.OrderPaymentTimeoutEvent;
import com.linklife.trade.lifecycle.timeout.OrderTimeoutRocketMqProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Outbox 多事件路由（017J-B）：唯一生产 {@link OutboxEventHandler}。
 *
 * <p>按 (eventType, eventVersion) 精确路由到 {@link OutboxBusinessHandler}：
 * 路由键重复启动失败；eventType/version 缺失或不支持返回稳定 fatal；
 * Router 不更新 Outbox，不捕获业务 Handler RuntimeException（由轮询框架映射为
 * HANDLER_EXCEPTION）。enabled=true 且必要路由（ORDER_CLOSED V1、
 * SECKILL_VOUCHER_CREATED V1）缺失时启动 fail-closed。</p>
 */
@Component
@ConditionalOnProperty(prefix = "linklife.trade.outbox", name = "enabled", havingValue = "true")
public class OutboxEventRouter implements OutboxEventHandler {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventRouter.class);

    private static final String REQUIRED_ORDER_CLOSED = "ORDER_CLOSED:1";
    private static final String REQUIRED_SECKILL_VOUCHER_CREATED = "SECKILL_VOUCHER_CREATED:1";

    @Resource
    private List<OutboxBusinessHandler> businessHandlers;

    @Autowired(required = false)
    private OrderTimeoutRocketMqProperties rocketMqProperties;

    private final Map<String, OutboxBusinessHandler> routes = new HashMap<>();

    @PostConstruct
    void registerRoutes() {
        for (OutboxBusinessHandler handler : businessHandlers) {
            String key = routeKey(handler.eventType(), handler.eventVersion());
            OutboxBusinessHandler existing = routes.putIfAbsent(key, handler);
            if (existing != null) {
                throw new IllegalStateException(
                        "Outbox 路由键重复，fail-closed：" + key);
            }
            log.info("已注册 Outbox 业务路由 key={}, handler={}", key, handler.getClass().getSimpleName());
        }
        if (!routes.containsKey(REQUIRED_ORDER_CLOSED)
                || !routes.containsKey(REQUIRED_SECKILL_VOUCHER_CREATED)) {
            throw new IllegalStateException(
                    "Outbox 必要路由缺失，fail-closed（需要 ORDER_CLOSED V1 与 SECKILL_VOUCHER_CREATED V1）");
        }
        String timeoutRoute = routeKey(
                OrderPaymentTimeoutEvent.EVENT_TYPE, OrderPaymentTimeoutEvent.EVENT_VERSION);
        if (rocketMqProperties != null && rocketMqProperties.isEnabled()
                && !routes.containsKey(timeoutRoute)) {
            throw new IllegalStateException(
                    "RocketMQ timeout 已启用但 Outbox timeout publish 路由缺失，fail-closed");
        }
    }

    @Override
    public OutboxHandleResult handle(OutboxEvent event) {
        String eventType = event.getEventType();
        Integer eventVersion = event.getEventVersion();
        if (eventType == null || eventType.isBlank() || eventVersion == null) {
            return OutboxHandleResult.fatal("OUTBOX_ROUTE_UNSUPPORTED");
        }
        OutboxBusinessHandler handler = routes.get(routeKey(eventType, eventVersion));
        if (handler == null) {
            return OutboxHandleResult.fatal("OUTBOX_ROUTE_UNSUPPORTED");
        }
        // 不捕获业务 Handler 的 RuntimeException：由轮询框架映射为可重试 HANDLER_EXCEPTION
        return handler.handle(event);
    }

    private String routeKey(String eventType, int eventVersion) {
        return eventType + ":" + eventVersion;
    }
}
