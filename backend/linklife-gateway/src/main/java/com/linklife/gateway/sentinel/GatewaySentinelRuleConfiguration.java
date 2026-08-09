package com.linklife.gateway.sentinel;

import com.alibaba.csp.sentinel.adapter.gateway.common.SentinelGatewayConstants;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiDefinition;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.ApiPathPredicateItem;
import com.alibaba.csp.sentinel.adapter.gateway.common.api.GatewayApiDefinitionManager;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.BlockRequestHandler;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gateway 精确热点 API 流控：只保护 blog/hot、shop/of/type、seckill 三类资源，
 * 不使用整个 routeId 粗粒度限流。
 *
 * <p>启动时一次性加载 ApiDefinition 与 FlowRule（QPS、intervalSec=1、默认 control behavior），
 * 并注册 429 JSON block handler；enabled=true 且任一 QPS 非法时 fail-fast。</p>
 */
@Configuration
@EnableConfigurationProperties(GatewaySentinelProperties.class)
public class GatewaySentinelRuleConfiguration {

    public static final String API_BLOG_HOT = "linklife-api-blog-hot";
    public static final String API_SHOP_OF_TYPE = "linklife-api-shop-of-type";
    public static final String API_SECKILL = "linklife-api-seckill";
    public static final String BLOCK_MESSAGE = "请求过于频繁，请稍后再试";

    @Bean
    public InitializingBean gatewaySentinelRuleInitializer(GatewaySentinelProperties properties) {
        return () -> {
            properties.validate();
            if (!properties.isEnabled()) {
                return;
            }
            GatewayApiDefinitionManager.loadApiDefinitions(Set.copyOf(buildApiDefinitions()));
            GatewayRuleManager.loadRules(Set.copyOf(buildFlowRules(properties)));
            GatewayCallbackManager.setBlockHandler(blockHandler());
        };
    }

    /**
     * 精确 API definition：blog/hot 与 shop/of/type 精确匹配；seckill 前缀匹配。
     */
    public static List<ApiDefinition> buildApiDefinitions() {
        return List.of(
                new ApiDefinition(API_BLOG_HOT).setPredicateItems(Set.of(
                        new ApiPathPredicateItem().setPattern("/api/blog/hot")
                                .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_EXACT))),
                new ApiDefinition(API_SHOP_OF_TYPE).setPredicateItems(Set.of(
                        new ApiPathPredicateItem().setPattern("/api/shop/of/type")
                                .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_EXACT))),
                new ApiDefinition(API_SECKILL).setPredicateItems(Set.of(
                        new ApiPathPredicateItem().setPattern("/api/voucher-order/seckill/**")
                                .setMatchStrategy(SentinelGatewayConstants.URL_MATCH_STRATEGY_PREFIX))));
    }

    public static List<GatewayFlowRule> buildFlowRules(GatewaySentinelProperties properties) {
        return List.of(
                flowRule(API_BLOG_HOT, properties.getHotBlogQps()),
                flowRule(API_SHOP_OF_TYPE, properties.getShopOfTypeQps()),
                flowRule(API_SECKILL, properties.getSeckillQps()));
    }

    private static GatewayFlowRule flowRule(String resource, double qps) {
        return new GatewayFlowRule(resource)
                .setResourceMode(SentinelGatewayConstants.RESOURCE_MODE_CUSTOM_API_NAME)
                .setGrade(RuleConstant.FLOW_GRADE_QPS)
                .setCount(qps)
                .setIntervalSec(1);
    }

    /**
     * 429 + application/json;charset=UTF-8，语义与 Result 对齐；不泄露 Sentinel 异常堆栈。
     */
    public static BlockRequestHandler blockHandler() {
        return (exchange, throwable) -> ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                .contentType(MediaType.parseMediaType("application/json;charset=UTF-8"))
                .body(BodyInserters.fromValue(blockBody()));
    }

    public static Map<String, Object> blockBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("errorMsg", BLOCK_MESSAGE);
        body.put("data", null);
        body.put("total", null);
        return body;
    }
}
