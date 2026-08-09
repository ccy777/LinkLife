package com.linklife.social.sentinel;

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.linklife.social.client.IdentityUserDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identity 熔断规则初始加载：单一 resource、exception-ratio、阈值与统计参数精确。
 */
class IdentitySentinelRuleConfigurationTest {

    @BeforeEach
    @AfterEach
    void clearSentinelState() {
        DegradeRuleManager.loadRules(List.of());
    }

    @Test
    void initializerLoadsSingleExceptionRatioRuleForSharedResource() throws Exception {
        IdentitySentinelProperties props = new IdentitySentinelProperties();
        props.setExceptionRatio(0.3);
        props.setMinimumRequestAmount(4);
        props.setStatIntervalMs(8000);
        props.setTimeWindowSeconds(2);

        new IdentitySentinelRuleConfiguration()
                .identitySentinelRuleInitializer(props).afterPropertiesSet();

        Set<DegradeRule> rules = DegradeRuleManager.getRulesOfResource(IdentityUserDirectory.RESOURCE_NAME);
        assertThat(rules).hasSize(1);
        DegradeRule rule = rules.iterator().next();
        assertThat(rule.getResource()).isEqualTo(IdentityUserDirectory.RESOURCE_NAME);
        assertThat(rule.getGrade()).isEqualTo(RuleConstant.DEGRADE_GRADE_EXCEPTION_RATIO);
        assertThat(rule.getCount()).isEqualTo(0.3);
        assertThat(rule.getMinRequestAmount()).isEqualTo(4);
        assertThat(rule.getStatIntervalMs()).isEqualTo(8000);
        assertThat(rule.getTimeWindow()).isEqualTo(2);
    }

    @Test
    void disabledInitializerLoadsNoRules() throws Exception {
        IdentitySentinelProperties props = new IdentitySentinelProperties();
        props.setEnabled(false);

        new IdentitySentinelRuleConfiguration()
                .identitySentinelRuleInitializer(props).afterPropertiesSet();

        assertThat(DegradeRuleManager.getRulesOfResource(IdentityUserDirectory.RESOURCE_NAME)).isEmpty();
    }
}
