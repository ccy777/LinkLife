package com.linklife.gateway;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gateway 依赖边界契约：WebFlux/Netty；拒绝 starter-web / spring-webmvc / MyBatis / MySQL / common-web。
 */
class GatewayDependencyBoundaryTest {

    private static void assertClassAbsent(String className) {
        try {
            Class.forName(className, false, GatewayDependencyBoundaryTest.class.getClassLoader());
            throw new AssertionError("gateway 不得依赖: " + className);
        } catch (ClassNotFoundException expected) {
            // absent is the contract
        }
    }

    @Test
    void mustNotDependOnServletMvc() {
        assertClassAbsent("org.springframework.web.servlet.DispatcherServlet");
        assertClassAbsent("org.springframework.web.servlet.config.annotation.WebMvcConfigurer");
    }

    @Test
    void mustNotDependOnMyBatisOrMySql() {
        assertClassAbsent("org.apache.ibatis.session.SqlSessionFactory");
        assertClassAbsent("com.mysql.cj.jdbc.Driver");
    }

    @Test
    void usesReactiveRedisNotBlockingRedis() throws Exception {
        // 类必须存在：reactive Redis 是 Gateway 会话认证的基础
        Class.forName("org.springframework.data.redis.core.ReactiveStringRedisTemplate");
        // 源码扫描：Gateway 正式代码不得引用阻塞模板
        java.nio.file.Files.walk(java.nio.file.Paths.get("src/main/java"))
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        String source = java.nio.file.Files.readString(p);
                        org.assertj.core.api.Assertions.assertThat(source)
                                .as("Gateway 不得使用阻塞 Redis/MyBatis: " + p)
                                .doesNotContain("import org.springframework.data.redis.core.StringRedisTemplate;")
                                .doesNotContain("import org.springframework.data.redis.core.RedisTemplate;")
                                .doesNotContain("import org.apache.ibatis")
                                .doesNotContain("import com.mysql");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    @Test
    void pomDeclaresRequiredStartersAndForbidsServletStack() throws Exception {
        String pom = Files.readString(Paths.get("pom.xml"));
        assertThat(pom).contains("spring-cloud-starter-gateway-server-webflux");
        assertThat(pom).contains("spring-cloud-starter-loadbalancer");
        assertThat(pom).contains("spring-cloud-starter-alibaba-nacos-discovery");
        assertThat(pom).contains("spring-cloud-starter-alibaba-sentinel");
        assertThat(pom).contains("spring-cloud-alibaba-sentinel-gateway");
        assertThat(pom).contains("spring-boot-starter-data-redis-reactive");
        assertThat(pom).contains("linklife-common-core");

        assertThat(pom)
                .as("Gateway POM 不得声明 Servlet/MVC/MyBatis/MySQL/common-web")
                .doesNotContain("spring-boot-starter-web")
                .doesNotContain("spring-webmvc")
                .doesNotContain("mybatis")
                .doesNotContain("mysql-connector")
                .doesNotContain("linklife-common-web");
    }
}
