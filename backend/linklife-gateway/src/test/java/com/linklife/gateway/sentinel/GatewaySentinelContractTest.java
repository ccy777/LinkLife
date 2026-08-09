package com.linklife.gateway.sentinel;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Stage 5A Gateway Sentinel 契约测试：三类精确 API definition、FlowRule 属性、
 * fail-fast 配置校验、429 JSON block 响应，以及依赖边界。
 */
class GatewaySentinelContractTest {

    @AfterEach
    void clearSentinelGatewayGlobalState() {
        GatewayRuleManager.loadRules(Set.of());
        GatewayApiDefinitionManager.loadApiDefinitions(Set.of());
    }

    @Test
    void apiDefinitionsCoverExactlyThreeHotApis() {
        List<ApiDefinition> defs = GatewaySentinelRuleConfiguration.buildApiDefinitions();
        assertThat(defs).extracting(ApiDefinition::getApiName)
                .containsExactlyInAnyOrder(
                        "linklife-api-blog-hot",
                        "linklife-api-shop-of-type",
                        "linklife-api-seckill");

        for (ApiDefinition def : defs) {
            assertThat(def.getPredicateItems()).isNotEmpty();
            for (com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPredicateItem predicate
                    : def.getPredicateItems()) {
                ApiPathPredicateItem item = (ApiPathPredicateItem) predicate;
                assertThat(item.getPattern()).isNotBlank();
            }
        }
    }

    @Test
    void nonHotApisAreNotCoveredByAnyDefinition() {
        List<String> negativePaths = List.of(
                "/api/blog",
                "/api/blog/1",
                "/api/blog/like/1",
                "/api/shop",
                "/api/shop/1",
                "/api/voucher-order/1/cancel",
                "/api/voucher-order/seckillX");
        for (ApiDefinition def : GatewaySentinelRuleConfiguration.buildApiDefinitions()) {
            for (com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPredicateItem predicate
                    : def.getPredicateItems()) {
                ApiPathPredicateItem item = (ApiPathPredicateItem) predicate;
                for (String path : negativePaths) {
                    assertThat(matches(item, path))
                            .as("路径 %s 不得被 %s/%s 命中", path, def.getApiName(), item.getPattern())
                            .isFalse();
                }
            }
        }
    }

    @Test
    void hotPathsAreCoveredByExpectedDefinitions() {
        assertThat(matchesAny("linklife-api-blog-hot", "/api/blog/hot")).isTrue();
        assertThat(matchesAny("linklife-api-shop-of-type", "/api/shop/of/type")).isTrue();
        assertThat(matchesAny("linklife-api-seckill", "/api/voucher-order/seckill/123")).isTrue();
        assertThat(matchesAny("linklife-api-blog-hot", "/api/blog")).isFalse();
        assertThat(matchesAny("linklife-api-shop-of-type", "/api/shop")).isFalse();
        assertThat(matchesAny("linklife-api-seckill", "/api/voucher-order/1/cancel")).isFalse();
    }

    @Test
    void lowThresholdRulesArePreciseAndLoadIntoManager() {
        GatewaySentinelProperties props = new GatewaySentinelProperties();
        props.setHotBlogQps(1);
        props.setShopOfTypeQps(2);
        props.setSeckillQps(3);

        List<GatewayFlowRule> rules = GatewaySentinelRuleConfiguration.buildFlowRules(props);
        assertThat(rules).hasSize(3);
        assertThat(rules).allSatisfy(rule -> {
            assertThat(rule.getResourceMode())
                    .isEqualTo(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME);
            assertThat(rule.getGrade()).isEqualTo(RuleConstant.FLOW_GRADE_QPS);
            assertThat(rule.getIntervalSec()).isEqualTo(1);
            assertThat(rule.getControlBehavior())
                    .isEqualTo(RuleConstant.CONTROL_BEHAVIOR_DEFAULT);
        });

        GatewayRuleManager.loadRules(Set.copyOf(rules));
        assertThat(GatewayRuleManager.getRulesForResource("linklife-api-blog-hot"))
                .singleElement().satisfies(r -> assertThat(r.getCount()).isEqualTo(1.0));
        assertThat(GatewayRuleManager.getRulesForResource("linklife-api-shop-of-type"))
                .singleElement().satisfies(r -> assertThat(r.getCount()).isEqualTo(2.0));
        assertThat(GatewayRuleManager.getRulesForResource("linklife-api-seckill"))
                .singleElement().satisfies(r -> assertThat(r.getCount()).isEqualTo(3.0));
    }

    @Test
    void enabledWithNonPositiveQpsFailsFast() {
        GatewaySentinelProperties props = new GatewaySentinelProperties();
        props.setEnabled(true);
        props.setHotBlogQps(0);
        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QPS");

        GatewaySentinelProperties seckill = new GatewaySentinelProperties();
        seckill.setEnabled(true);
        seckill.setSeckillQps(-1);
        assertThatThrownBy(seckill::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledWithNonPositiveQpsDoesNotFailFast() {
        GatewaySentinelProperties props = new GatewaySentinelProperties();
        props.setEnabled(false);
        props.setHotBlogQps(0);
        props.validate();
    }

    @Test
    void blockHandlerReturns429JsonWithResultSemantics() throws Exception {
        String body = blockResponseBody(new FlowException("blocked"));
        assertThat(body).contains("\"success\":false")
                .contains("\"errorMsg\":\"请求过于频繁，请稍后再试\"")
                .contains("\"data\":null")
                .contains("\"total\":null");

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> parsed = new ObjectMapper()
                .readValue(body, java.util.Map.class);
        assertThat(parsed.get("success")).isEqualTo(Boolean.FALSE);
        assertThat(parsed.get("errorMsg")).isEqualTo("请求过于频繁，请稍后再试");
        assertThat(parsed).containsEntry("data", null).containsEntry("total", null);
    }

    private static String blockResponseBody(Throwable ex) throws Exception {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/blog/hot").build());
        ServerResponse response = GatewaySentinelRuleConfiguration.blockHandler()
                .handleRequest(exchange, ex).block();
        assertThat(response.statusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.headers().getContentType())
                .isEqualTo(org.springframework.http.MediaType.parseMediaType("application/json;charset=UTF-8"));

        ServerCodecConfigurer codecs = ServerCodecConfigurer.create();
        response.writeTo(exchange, new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return codecs.getWriters();
            }

            @Override
            public List<org.springframework.web.reactive.result.view.ViewResolver> viewResolvers() {
                return java.util.List.of();
            }
        }).block();
        return exchange.getResponse().getBodyAsString().block();
    }

    private static boolean matchesAny(String apiName, String path) {
        for (ApiDefinition def : GatewaySentinelRuleConfiguration.buildApiDefinitions()) {
            if (!def.getApiName().equals(apiName)) {
                continue;
            }
            for (com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPredicateItem predicate
                    : def.getPredicateItems()) {
                ApiPathPredicateItem item = (ApiPathPredicateItem) predicate;
                if (matches(item, path)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matches(ApiPathPredicateItem item, String path) {
        return switch (item.getMatchStrategy()) {
            case SentinelGatewayConstants.URL_MATCH_STRATEGY_EXACT -> item.getPattern().equals(path);
            case SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX ->
                    new AntPathMatcher().match(item.getPattern(), path);
            case SentinelGatewayConstants.URL_MATCH_STRATEGY_REGEX -> Pattern.matches(item.getPattern(), path);
            default -> false;
        };
    }
}
