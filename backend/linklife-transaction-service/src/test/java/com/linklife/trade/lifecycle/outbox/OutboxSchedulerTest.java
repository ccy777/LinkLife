package com.linklife.trade.lifecycle.outbox;

import com.linklife.trade.application.OutboxPollingService;
import com.linklife.trade.lifecycle.timeout.OrderTimeoutProperties;
import com.linklife.promotion.service.ISeckillVoucherService;
import com.linklife.trade.mapper.VoucherOrderMapper;
import com.linklife.trade.mapper.OutboxEventMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OutboxScheduler 与 Bean 条件契约测试：默认关闭、显式 enabled=true 才创建、
 * 缺 handler 时 fail-closed、调度器只依赖轮询服务、成功汇总、不吞异常、无 HTTP 接口。
 */
class OutboxSchedulerTest {

    @Test
    void schedulerDependsOnlyOnPollingService() {
        OutboxPollingService pollingService = mock(OutboxPollingService.class);
        OutboxScheduler scheduler = new OutboxScheduler(pollingService);

        assertThat(scheduler).isNotNull();
        assertThat(Arrays.stream(OutboxScheduler.class.getDeclaredFields())
                .filter(f -> !java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                .map(Field::getName))
                .containsExactly("outboxPollingService");
    }

    @Test
    void successInvokesServiceAndPassesSummary() {
        OutboxPollingService pollingService = mock(OutboxPollingService.class);
        when(pollingService.pollDueEvents())
                .thenReturn(new OutboxPollResult(1, 10, 8, 7, 1, 1, 1, 0, false));
        OutboxScheduler scheduler = new OutboxScheduler(pollingService);

        scheduler.pollDueEvents();

        verify(pollingService).pollDueEvents();
    }

    @Test
    void serviceFailureIsNotSwallowed() {
        OutboxPollingService pollingService = mock(OutboxPollingService.class);
        when(pollingService.pollDueEvents())
                .thenThrow(new IllegalStateException("db down"));
        OutboxScheduler scheduler = new OutboxScheduler(pollingService);

        assertThatThrownBy(() -> scheduler.pollDueEvents())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db down");
    }

    @Test
    void scheduledAnnotationReadsDelayProperties() throws Exception {
        Method method = OutboxScheduler.class.getMethod("pollDueEvents");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${linklife.trade.outbox.scan-delay-ms}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${linklife.trade.outbox.initial-delay-ms}");
    }

    @Test
    void schedulingConfigurationEnablesScheduling() {
        assertThat(OutboxSchedulingConfiguration.class
                .getAnnotation(Configuration.class)).isNotNull();
        assertThat(OutboxSchedulingConfiguration.class
                .getAnnotation(EnableScheduling.class)).isNotNull();
    }

    @Test
    void beanConditionRequiresExplicitTrue() throws Exception {
        Method method = OutboxSchedulingConfiguration.class.getMethod(
                "outboxScheduler", OutboxPollingService.class);
        ConditionalOnProperty condition = method.getAnnotation(ConditionalOnProperty.class);

        assertThat(condition).isNotNull();
        assertThat(condition.prefix()).isEqualTo("linklife.trade.outbox");
        assertThat(condition.name()).containsExactly("enabled");
        assertThat(condition.havingValue()).isEqualTo("true");
        assertThat(condition.matchIfMissing()).isFalse();
    }

    @Test
    void defaultDisabledContract() throws Exception {
        assertThat(new OutboxProperties().isEnabled()).isFalse();

        String yaml = new String(Files.readAllBytes(
                Paths.get("src/main/resources/application.yaml")), StandardCharsets.UTF_8)
                .replace("\r\n", "\n");
        assertThat(yaml).contains("enabled: ${LINKLIFE_OUTBOX_ENABLED:false}");
    }

    @Test
    void schedulerDoesNotTouchMapperRedisOrHttp() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/main/java/com/linklife/trade/lifecycle/outbox/OutboxScheduler.java")),
                StandardCharsets.UTF_8);

        assertThat(source)
                .doesNotContain("import com.linklife.trade.mapper")
                .doesNotContain("import org.springframework.data.redis")
                .doesNotContain("@RestController")
                .doesNotContain("@PostMapping")
                .doesNotContain("HttpServletRequest");

        assertThat(Arrays.stream(OutboxScheduler.class.getDeclaredFields())
                .map(Field::getType)
                .map(Class::getName))
                .doesNotContain("com.linklife.trade.mapper.OutboxEventMapper");
    }

    @Configuration
    @Import({OutboxPollingService.class, OutboxSchedulingConfiguration.class})
    static class OutboxTestConfig {
    }

    @Configuration
    @Import({OutboxPollingService.class, OutboxSchedulingConfiguration.class,
            OutboxEventRouter.class,
            OrderClosedOutboxEventHandler.class, RedisOrderCloseCompensationAdapter.class,
            SeckillVoucherCreatedOutboxEventHandler.class, SeckillVoucherInitializeAdapter.class})
    static class OutboxRealHandlerTestConfig {
    }

    @Configuration
    @Import({OutboxPollingService.class, OutboxSchedulingConfiguration.class,
            OutboxEventRouter.class,
            OrderClosedOutboxEventHandler.class, RedisOrderCloseCompensationAdapter.class})
    static class OutboxMissingSeckillRouteTestConfig {
    }

    @Test
    void disabledByDefaultDoesNotCreatePollerOrScheduler() {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxTestConfig.class)
                .withBean(OutboxEventMapper.class, () -> mock(OutboxEventMapper.class))
                .withBean(OutboxProperties.class, OutboxProperties::new)
                .withPropertyValues("linklife.trade.outbox.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(OutboxPollingService.class);
                    assertThat(context).doesNotHaveBean(OutboxScheduler.class);
                });
    }

    @Test
    void enabledWithoutHandlerFailsClosed() {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxTestConfig.class)
                .withBean(OutboxEventMapper.class, () -> mock(OutboxEventMapper.class))
                .withBean(OutboxProperties.class, OutboxProperties::new)
                .withPropertyValues(
                        "linklife.trade.outbox.enabled=true",
                        "linklife.trade.outbox.scan-delay-ms=5000",
                        "linklife.trade.outbox.initial-delay-ms=30000")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void enabledWithHandlerCreatesPollerAndScheduler() {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxTestConfig.class)
                .withBean(OutboxEventMapper.class, () -> mock(OutboxEventMapper.class))
                .withBean(OutboxProperties.class, OutboxProperties::new)
                .withBean(OrderTimeoutProperties.class, OrderTimeoutProperties::new)
                .withBean(OutboxEventHandler.class, () -> mock(OutboxEventHandler.class))
                .withPropertyValues(
                        "linklife.trade.outbox.enabled=true",
                        "linklife.trade.outbox.scan-delay-ms=5000",
                        "linklife.trade.outbox.initial-delay-ms=30000")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxPollingService.class);
                    assertThat(context).hasSingleBean(OutboxScheduler.class);
                });
    }

    @Test
    void enabledWithRealHandlerAndDependenciesCreatesEverything() {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxRealHandlerTestConfig.class)
                .withBean(OutboxEventMapper.class, () -> mock(OutboxEventMapper.class))
                .withBean(VoucherOrderMapper.class, () -> mock(VoucherOrderMapper.class))
                .withBean(ISeckillVoucherService.class, () -> mock(ISeckillVoucherService.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(ObjectMapper.class, () -> {
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.registerModule(new JavaTimeModule());
                    return mapper;
                })
                .withBean(OutboxProperties.class, OutboxProperties::new)
                .withBean(OrderTimeoutProperties.class, OrderTimeoutProperties::new)
                .withPropertyValues(
                        "linklife.trade.outbox.enabled=true",
                        "linklife.trade.outbox.scan-delay-ms=5000",
                        "linklife.trade.outbox.initial-delay-ms=30000")
                .run(context -> {
                    assertThat(context).hasSingleBean(OutboxEventRouter.class);
                    assertThat(context).hasSingleBean(OrderClosedOutboxEventHandler.class);
                    assertThat(context).hasSingleBean(SeckillVoucherCreatedOutboxEventHandler.class);
                    assertThat(context).hasSingleBean(OutboxPollingService.class);
                    assertThat(context).hasSingleBean(OutboxScheduler.class);
                });
    }

    @Test
    void enabledWithMissingRequiredRouteFailsClosed() {
        new ApplicationContextRunner()
                .withUserConfiguration(OutboxMissingSeckillRouteTestConfig.class)
                .withBean(OutboxEventMapper.class, () -> mock(OutboxEventMapper.class))
                .withBean(VoucherOrderMapper.class, () -> mock(VoucherOrderMapper.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(ObjectMapper.class, () -> {
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.registerModule(new JavaTimeModule());
                    return mapper;
                })
                .withBean(OutboxProperties.class, OutboxProperties::new)
                .withPropertyValues(
                        "linklife.trade.outbox.enabled=true",
                        "linklife.trade.outbox.scan-delay-ms=5000",
                        "linklife.trade.outbox.initial-delay-ms=30000")
                .run(context -> assertThat(context).hasFailed());
    }
}
