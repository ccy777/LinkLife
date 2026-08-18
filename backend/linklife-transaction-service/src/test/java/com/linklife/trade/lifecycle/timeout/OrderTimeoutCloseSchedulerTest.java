package com.linklife.trade.lifecycle.timeout;

import com.linklife.trade.application.OrderTimeoutCloseService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OrderTimeoutCloseScheduler 单元测试：只依赖关闭服务、成功汇总、失败不伪造成功、
 * @Scheduled 配置、enabled 显式 true 条件、默认 false 契约、不访问 Mapper/Redis/库存。
 */
class OrderTimeoutCloseSchedulerTest {

    private static final Instant FIXED_CUTOFF = Instant.parse("2026-08-06T10:00:00Z");

    @Test
    void schedulerDependsOnlyOnCloseService() {
        OrderTimeoutCloseService service = mock(OrderTimeoutCloseService.class);
        OrderTimeoutCloseScheduler scheduler = new OrderTimeoutCloseScheduler(service);

        assertThat(scheduler).isNotNull();
        assertThat(Arrays.stream(OrderTimeoutCloseScheduler.class.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(Field::getName))
                .containsExactly("orderTimeoutCloseService");
    }

    @Test
    void successInvokesServiceAndPassesSummary() {
        OrderTimeoutCloseService service = mock(OrderTimeoutCloseService.class);
        when(service.closeExpiredOrders())
                .thenReturn(new OrderTimeoutCloseResult(FIXED_CUTOFF, 1, 10, 8, 2, false));
        OrderTimeoutCloseScheduler scheduler = new OrderTimeoutCloseScheduler(service);

        scheduler.closeExpiredOrders();

        verify(service).closeExpiredOrders();
    }

    @Test
    void serviceFailureIsNotSwallowed() {
        OrderTimeoutCloseService service = mock(OrderTimeoutCloseService.class);
        when(service.closeExpiredOrders())
                .thenThrow(new IllegalStateException("db down"));
        OrderTimeoutCloseScheduler scheduler = new OrderTimeoutCloseScheduler(service);

        assertThatThrownBy(() -> scheduler.closeExpiredOrders())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }

    @Test
    void scheduledAnnotationReadsDelayProperties() throws Exception {
        Method method = OrderTimeoutCloseScheduler.class.getMethod("closeExpiredOrders");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${linklife.trade.order-timeout.scan-delay-ms}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${linklife.trade.order-timeout.initial-delay-ms}");
    }

    @Test
    void schedulingConfigurationEnablesScheduling() {
        assertThat(OrderTimeoutSchedulingConfiguration.class
                .getAnnotation(Configuration.class)).isNotNull();
        assertThat(OrderTimeoutSchedulingConfiguration.class
                .getAnnotation(EnableScheduling.class)).isNotNull();
    }

    @Test
    void beanConditionRequiresExplicitTrue() throws Exception {
        Method method = OrderTimeoutSchedulingConfiguration.class.getMethod(
                "orderTimeoutCloseScheduler", OrderTimeoutCloseService.class);
        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("linklife.trade.order-timeout");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void defaultDisabledContract() throws Exception {
        assertThat(new OrderTimeoutProperties().isEnabled()).isFalse();

        String yaml = new String(Files.readAllBytes(
                Paths.get("src/main/resources/application.yaml")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertThat(yaml).contains("enabled: ${LINKLIFE_ORDER_TIMEOUT_ENABLED:false}");
    }

    @Test
    void schedulerDoesNotTouchMapperRedisOrStock() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/lifecycle/timeout/OrderTimeoutCloseScheduler.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("import com.linklife.trade.mapper")
                .doesNotContain("import org.springframework.data.redis")
                .doesNotContain("import com.linklife.identity.security.UserHolder")
                .doesNotContain("import com.linklife.promotion.service.ISeckillVoucherService")
                .doesNotContain("import com.linklife.trade.messaging")
                .doesNotContain("import org.redisson")
                .doesNotContain("import com.linklife.trade.submission.RedisOrderSubmissionStatusRepository");

        assertThat(Arrays.stream(OrderTimeoutCloseScheduler.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName))
                .doesNotContain(
                        "com.linklife.trade.mapper.VoucherOrderMapper",
                        "org.springframework.data.redis.core.StringRedisTemplate",
                        "com.linklife.promotion.service.ISeckillVoucherService");
    }
}
