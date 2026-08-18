package com.linklife.common.core.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * common-core 依赖边界契约：只允许纯 Java，不得出现 Servlet/WebFlux/MyBatis/Redis/业务依赖。
 */
class CommonCoreBoundaryTest {

    private static void assertClassAbsent(String className) throws ClassNotFoundException {
        try {
            Class.forName(className, false, CommonCoreBoundaryTest.class.getClassLoader());
            throw new AssertionError("common-core 不得依赖: " + className);
        } catch (ClassNotFoundException expected) {
            // absent is the contract
        }
    }

    @Test
    void mustNotDependOnServlet() throws Exception {
        assertClassAbsent("org.springframework.web.servlet.DispatcherServlet");
    }

    @Test
    void mustNotDependOnWebFlux() throws Exception {
        assertClassAbsent("org.springframework.web.reactive.DispatcherHandler");
    }

    @Test
    void mustNotDependOnMyBatis() throws Exception {
        assertClassAbsent("org.apache.ibatis.session.SqlSessionFactory");
    }

    @Test
    void mustNotDependOnRedis() throws Exception {
        assertClassAbsent("org.springframework.data.redis.core.StringRedisTemplate");
        assertClassAbsent("org.redisson.api.RedissonClient");
    }

    @Test
    void contractConstantsAreFrozen() {
        assertThat(InternalHeaders.X_LINKLIFE_USER_ID).isEqualTo("X-LinkLife-User-Id");
        assertThat(InternalHeaders.X_LINKLIFE_USER_NICK).isEqualTo("X-LinkLife-User-Nick");
        assertThat(InternalHeaders.X_LINKLIFE_USER_ICON).isEqualTo("X-LinkLife-User-Icon");

        assertThat(ServiceNames.GATEWAY).isEqualTo("linklife-gateway");
        assertThat(ServiceNames.IDENTITY).isEqualTo("linklife-identity-service");
        assertThat(ServiceNames.MERCHANT).isEqualTo("linklife-merchant-service");
        assertThat(ServiceNames.TRANSACTION).isEqualTo("linklife-transaction-service");
        assertThat(ServiceNames.SOCIAL).isEqualTo("linklife-social-service");
    }
}
