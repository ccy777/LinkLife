package com.linklife.social.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.linklife.social.client.IdentityUserDirectory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Social → Identity 单一下游资源的 exception-ratio 熔断规则（启动时一次性加载）。
 */
@Configuration
@EnableConfigurationProperties(IdentitySentinelProperties.class)
public class IdentitySentinelRuleConfiguration {

    @Bean
    public InitializingBean identitySentinelRuleInitializer(IdentitySentinelProperties properties) {
        return () -> {
            properties.validate();
            if (!properties.isEnabled()) {
                return;
            }
            DegradeRule rule = new DegradeRule(IdentityUserDirectory.RESOURCE_NAME)
                    .setGrade(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO)
                    .setCount(properties.getExceptionRatio())
                    .setMinRequestAmount(properties.getMinimumRequestAmount())
                    .setStatIntervalMs(properties.getStatIntervalMs())
                    .setTimeWindow(properties.getTimeWindowSeconds());
            DegradeRuleManager.loadRules(List.of(rule));
        };
    }
}
