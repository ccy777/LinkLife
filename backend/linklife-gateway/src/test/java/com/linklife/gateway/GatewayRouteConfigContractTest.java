package com.linklife.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway 4.3 YAML 契约（纯 JUnit，不启动上下文）：
 * 锁定 spring.cloud.gateway.server.webflux.routes 命名空间、单次 StripPrefix=1、lb:// 目标、
 * register-enabled=false、禁止遗留 spring.cloud.gateway.routes。
 */
class GatewayRouteConfigContractTest {

    private static final List<String> EXPECTED_ROUTE_IDS = List.of(
            "identity-user",
            "merchant-shop", "merchant-shop-type", "merchant-upload", "merchant-files",
            "transaction-voucher", "transaction-voucher-order",
            "social-blog", "social-follow", "social-blog-comments");

    @Test
    void yamlFreezesGateway43NamespaceAndNacosConsumerOnly() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("app", new ClassPathResource("application.yaml"));
        assertThat(sources).hasSize(1);
        PropertySource<?> source = sources.get(0);

        assertThat(source.getProperty("spring.application.name")).isEqualTo("linklife-gateway");
        assertThat(source.getProperty("spring.cloud.nacos.discovery.register-enabled")).isEqualTo(Boolean.FALSE);
        assertThat(source.getProperty("spring.cloud.nacos.discovery.server-addr"))
                .asString()
                .startsWith("${NACOS_ADDR:");

        assertThat(source.containsProperty("spring.cloud.gateway.server.webflux.routes[0].id")).isTrue();
        assertThat(source.containsProperty("spring.cloud.gateway.routes")).isFalse();

        int routeCount = 0;
        while (source.containsProperty("spring.cloud.gateway.server.webflux.routes[" + routeCount + "].id")) {
            routeCount++;
        }
        assertThat(routeCount).isEqualTo(10);

        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < routeCount; i++) {
            String prefix = "spring.cloud.gateway.server.webflux.routes[" + i + "]";
            assertThat(source.getProperty(prefix + ".filters[0]")).isEqualTo("StripPrefix=1");
            assertThat(source.getProperty(prefix + ".uri")).asString().startsWith("lb://linklife-");
            ids.add(String.valueOf(source.getProperty(prefix + ".id")));
        }
        assertThat(ids).containsExactlyInAnyOrderElementsOf(EXPECTED_ROUTE_IDS);
    }

    @Test
    void legacyRouteKeyAbsentInYaml() throws Exception {
        String yaml = Files.readString(Paths.get("src/main/resources/application.yaml"));
        assertThat(yaml).doesNotContain("spring.cloud.gateway.routes");
        assertThat(yaml).contains("webflux:");
        assertThat(yaml).contains("routes:");
        assertThat(yaml).contains("StripPrefix=1");
    }
}
