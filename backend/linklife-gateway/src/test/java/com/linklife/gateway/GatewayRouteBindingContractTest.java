package com.linklife.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway 4.3 路由真实绑定契约：启动 WebFlux 上下文（RANDOM_PORT），
 * 验证 10 条路由从 server.webflux 命名空间绑定、lb:// 目标、单次 StripPrefix=1、/api/** Path 谓词。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.cloud.nacos.discovery.enabled=false",
        "spring.cloud.nacos.discovery.register-enabled=false"
})
class GatewayRouteBindingContractTest {

    private static final List<String> EXPECTED_ROUTE_IDS = List.of(
            "identity-user",
            "merchant-shop", "merchant-shop-type", "merchant-upload", "merchant-files",
            "transaction-voucher", "transaction-voucher-order",
            "social-blog", "social-follow", "social-blog-comments");

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void routesBindFromServerWebfluxNamespace() {
        List<RouteDefinition> routes = routeDefinitionLocator.getRouteDefinitions().collectList().block();
        assertThat(routes).isNotNull();
        assertThat(routes).hasSize(10);

        List<String> ids = routes.stream().map(RouteDefinition::getId).sorted().toList();
        assertThat(ids).containsExactlyInAnyOrderElementsOf(EXPECTED_ROUTE_IDS);

        for (RouteDefinition route : routes) {
            assertThat(route.getUri().getScheme())
                    .as(route.getId() + " 必须使用 lb:// 服务发现")
                    .isEqualTo("lb");
            long stripPrefixFilters = route.getFilters().stream()
                    .filter(f -> "StripPrefix".equals(f.getName()) && f.getArgs().containsValue("1"))
                    .count();
            assertThat(stripPrefixFilters)
                    .as(route.getId() + " 必须且只 StripPrefix 一次")
                    .isEqualTo(1);
            boolean hasApiPathPredicate = route.getPredicates().stream()
                    .anyMatch(p -> "Path".equals(p.getName())
                            && p.getArgs().values().stream().anyMatch(v -> String.valueOf(v).startsWith("/api/")));
            assertThat(hasApiPathPredicate)
                    .as(route.getId() + " 必须包含 /api/** Path 谓词")
                    .isTrue();
        }
    }
}
