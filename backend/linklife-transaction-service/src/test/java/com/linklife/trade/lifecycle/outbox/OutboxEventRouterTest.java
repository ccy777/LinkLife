package com.linklife.trade.lifecycle.outbox;

import com.linklife.trade.entity.OutboxEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * OutboxEventRouter 单元测试：精确路由、重复路由启动失败、不支持 fatal、
 * RuntimeException 不吞、缺必要路由 fail-closed。
 */
class OutboxEventRouterTest {

    private OutboxEventRouter router;

    @BeforeEach
    void setUp() {
        router = new OutboxEventRouter();
    }

    private OutboxBusinessHandler handler(String eventType, int version) {
        OutboxBusinessHandler handler = mock(OutboxBusinessHandler.class);
        org.mockito.Mockito.when(handler.eventType()).thenReturn(eventType);
        org.mockito.Mockito.when(handler.eventVersion()).thenReturn(version);
        return handler;
    }

    private OutboxEvent event(String eventType, Integer version) {
        OutboxEvent event = new OutboxEvent();
        event.setEventType(eventType);
        event.setEventVersion(version);
        return event;
    }

    @Test
    void routesOrderClosedAndSeckillVoucherCreatedExactly() {
        OutboxBusinessHandler close = handler("ORDER_CLOSED", 1);
        OutboxBusinessHandler create = handler("SECKILL_VOUCHER_CREATED", 1);
        org.mockito.Mockito.when(close.handle(org.mockito.ArgumentMatchers.any()))
                .thenReturn(OutboxHandleResult.success());
        org.mockito.Mockito.when(create.handle(org.mockito.ArgumentMatchers.any()))
                .thenReturn(OutboxHandleResult.success());
        ReflectionTestUtils.setField(router, "businessHandlers", List.of(close, create));
        router.registerRoutes();

        assertThat(router.handle(event("ORDER_CLOSED", 1))).isEqualTo(OutboxHandleResult.success());
        verify(close).handle(org.mockito.ArgumentMatchers.any());
        verify(create, never()).handle(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void duplicateRouteKeyFailsClosed() {
        OutboxBusinessHandler first = handler("ORDER_CLOSED", 1);
        OutboxBusinessHandler second = handler("ORDER_CLOSED", 1);
        OutboxBusinessHandler create = handler("SECKILL_VOUCHER_CREATED", 1);
        ReflectionTestUtils.setField(router, "businessHandlers", List.of(first, second, create));

        assertThatThrownBy(() -> router.registerRoutes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("路由键重复");
    }

    @Test
    void missingRequiredRouteFailsClosed() {
        OutboxBusinessHandler close = handler("ORDER_CLOSED", 1);
        ReflectionTestUtils.setField(router, "businessHandlers", List.of(close));

        assertThatThrownBy(() -> router.registerRoutes())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必要路由缺失");
    }

    @Test
    void unsupportedEventTypeOrVersionIsFatal() {
        OutboxBusinessHandler close = handler("ORDER_CLOSED", 1);
        OutboxBusinessHandler create = handler("SECKILL_VOUCHER_CREATED", 1);
        ReflectionTestUtils.setField(router, "businessHandlers", List.of(close, create));
        router.registerRoutes();

        assertThat(router.handle(event("UNKNOWN", 1)).type())
                .isEqualTo(OutboxHandleResult.OutboxHandleResultType.FATAL_FAILURE);
        assertThat(router.handle(event("ORDER_CLOSED", 2)).type())
                .isEqualTo(OutboxHandleResult.OutboxHandleResultType.FATAL_FAILURE);
        assertThat(router.handle(event(null, 1)).type())
                .isEqualTo(OutboxHandleResult.OutboxHandleResultType.FATAL_FAILURE);
        assertThat(router.handle(event("ORDER_CLOSED", null)).type())
                .isEqualTo(OutboxHandleResult.OutboxHandleResultType.FATAL_FAILURE);
    }

    @Test
    void handlerRuntimeExceptionIsNotSwallowed() {
        OutboxBusinessHandler close = handler("ORDER_CLOSED", 1);
        OutboxBusinessHandler create = handler("SECKILL_VOUCHER_CREATED", 1);
        doThrow(new IllegalStateException("boom")).when(close).handle(org.mockito.ArgumentMatchers.any());
        ReflectionTestUtils.setField(router, "businessHandlers", List.of(close, create));
        router.registerRoutes();

        assertThatThrownBy(() -> router.handle(event("ORDER_CLOSED", 1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
    }

    @Test
    void onlyOneProductionOutboxEventHandler() throws Exception {
        String consumer = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/lifecycle/outbox/OrderClosedOutboxEventHandler.java")),
                StandardCharsets.UTF_8);
        assertThat(consumer).contains("implements OutboxBusinessHandler");
        assertThat(consumer).doesNotContain("implements OutboxEventHandler");

        String routerSource = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/lifecycle/outbox/OutboxEventRouter.java")),
                StandardCharsets.UTF_8);
        assertThat(routerSource).contains("implements OutboxEventHandler");
    }
}
