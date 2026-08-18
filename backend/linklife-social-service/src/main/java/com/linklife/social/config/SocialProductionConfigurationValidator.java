package com.linklife.social.config;

import com.linklife.common.core.config.RuntimeProfilePolicy;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.Locale;

/**
 * Social 生产配置守卫（最小子集）：错误只报告配置 key，不打印值；
 * 不含 Identity 验证码 / Merchant upload / Transaction timeout/outbox 配置。
 */
public class SocialProductionConfigurationValidator
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        validate(event.getEnvironment());
    }

    public void validate(Environment environment) {
        validateCommonRanges(environment);
        boolean prod = RuntimeProfilePolicy.isProductionProfile(environment);
        boolean enabled = environment.getProperty(
                "linklife.runtime.production-validation-enabled", Boolean.class, true);
        if (prod && enabled) {
            validateProduction(environment);
        }
    }

    private void validateCommonRanges(Environment environment) {
        requireNonBlank(environment, "spring.datasource.url");
        requireNonBlank(environment, "spring.data.redis.host");
        Integer redisPort = environment.getProperty("spring.data.redis.port", Integer.class);
        if (redisPort == null || redisPort < 1 || redisPort > 65535) {
            fail("spring.data.redis.port");
        }
        Integer redisDatabase = environment.getProperty("spring.data.redis.database", Integer.class);
        if (redisDatabase != null && redisDatabase < 0) {
            fail("spring.data.redis.database");
        }
        String timeout = environment.getProperty("spring.data.redis.timeout");
        if (timeout != null) {
            try {
                Duration duration = DurationStyle.detectAndParse(timeout);
                if (duration.isZero() || duration.isNegative()) {
                    fail("spring.data.redis.timeout");
                }
            } catch (Exception e) {
                fail("spring.data.redis.timeout");
            }
        }
    }

    private void validateProduction(Environment environment) {
        requireNonBlank(environment, "spring.datasource.username");
        requireNonBlank(environment, "spring.datasource.password");
        for (String key : new String[]{
                "spring.datasource.url",
                "spring.datasource.username",
                "spring.datasource.password",
                "spring.data.redis.host"
        }) {
            String value = environment.getProperty(key);
            if (value != null && containsPlaceholder(value)) {
                fail(key);
            }
        }
        for (String key : new String[]{
                "logging.level.com.linklife",
                "logging.level.com.linklife.social.mapper",
                "logging.level.org.apache.ibatis"
        }) {
            String value = environment.getProperty(key);
            if (value != null && ("DEBUG".equalsIgnoreCase(value) || "TRACE".equalsIgnoreCase(value))) {
                fail(key);
            }
        }
    }

    private boolean containsPlaceholder(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("change_me")
                || lower.contains("your-password")
                || lower.contains("example-password");
    }

    private void requireNonBlank(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            fail(key);
        }
    }

    private void fail(String configKey) {
        throw new IllegalStateException("配置校验失败：" + configKey);
    }
}
