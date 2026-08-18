package com.linklife.gateway.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway 不得为 Identity 内部 API 暴露 /api/internal/** 路由。
 */
class InternalApiRouteIsolationTest {

    @Test
    void gatewayRouteConfigHasNoInternalRoute() throws Exception {
        String yaml = java.nio.file.Files.readString(
                java.nio.file.Paths.get("src/main/resources/application.yaml"));
        assertThat(yaml).doesNotContain("Path=/api/internal/**");
        assertThat(yaml).doesNotContain("/internal/users");
    }
}
