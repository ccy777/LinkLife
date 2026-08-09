package com.linklife.identity.security;

import com.linklife.common.core.config.RuntimeProfilePolicy;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证码发布器生产最终防线与手机号脱敏测试。
 */
class VerificationCodePublisherTest {

    private VerificationCodePublisher publisherWithProfiles(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new VerificationCodePublisher(environment);
    }

    @Test
    void localProfilePublishesDevCode() {
        VerificationCodePublisher publisher = publisherWithProfiles("local");

        assertThat(publisher.publishDevCode("13800138000", "123456")).isTrue();
    }

    @Test
    void prodProfileNeverPublishes() {
        VerificationCodePublisher publisher = publisherWithProfiles("prod");

        assertThat(publisher.publishDevCode("13800138000", "123456")).isFalse();
    }

    @Test
    void productionProfileNeverPublishes() {
        VerificationCodePublisher publisher = publisherWithProfiles("production");

        assertThat(publisher.publishDevCode("13800138000", "123456")).isFalse();
    }

    @Test
    void productionValidationDisabledStillNeverPublishes() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("linklife.runtime.production-validation-enabled", "false");
        VerificationCodePublisher publisher = new VerificationCodePublisher(environment);

        assertThat(publisher.publishDevCode("13800138000", "123456")).isFalse();
    }

    @Test
    void policyAndPublisherAgreeOnProduction() {
        // 发布器与策略判断一致：production 属于生产
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("production");

        assertThat(RuntimeProfilePolicy.isProductionProfile(env)).isTrue();
        assertThat(new VerificationCodePublisher(env).publishDevCode("13800138000", "123456")).isFalse();
    }
}
