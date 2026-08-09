package com.linklife.gateway.audit;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 018F §19 最终静态统一审计：在最终 7-module reactor 上验证
 * Gateway/Identity/Merchant/Transaction/Social/Common 的模块边界、
 * 数据库/Redis namespace 归属、Gateway-only 入口与关键安全语义。
 *
 * <p>纯文件静态审计（不启动 Spring 上下文），工作目录为 gateway module 根。</p>
 */
class FinalStage4StaticAuditTest {

    private static String read(String first, String... more) {
        Path p = Paths.get(first, more);
        try {
            return Files.readString(p);
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 " + p.toAbsolutePath(), e);
        }
    }

    @Test
    void gatewayIsWebFluxOnlyWithReactiveRedisAndNoJdbc() {
        String pom = read("pom.xml");
        assertThat(pom).contains("spring-cloud-starter-gateway-server-webflux")
                .contains("spring-boot-starter-data-redis-reactive")
                .doesNotContain("spring-boot-starter-web")
                .doesNotContain("mybatis")
                .doesNotContain("mysql-connector-j");

        String yaml = read("src/main/resources/application.yaml");
        assertThat(yaml).contains("register-enabled: false");
        assertThat(yaml).doesNotContain("/api/internal");
        assertThat(yaml).doesNotContain("StripPrefix=2");
        long routes = yaml.lines().filter(l -> l.trim().startsWith("- id:")).count();
        long strips = yaml.lines().filter(l -> l.trim().equals("- StripPrefix=1")).count();
        assertThat(strips).as("每个 route 恰好 StripPrefix=1 一次").isEqualTo(routes);
    }

    @Test
    void gatewaySessionFilterKeepsFailClosedAndLegacyFallbackSemantics() {
        String filter = read("src/main/java/com/linklife/gateway/security/GatewaySessionAuthFilter.java");
        assertThat(filter).contains("stripInternalHeaders")
                .contains("X-LinkLife-")
                .contains("hasKey(newKey)")
                .contains("readAndRefresh(legacyKey)")
                .contains("defaultIfEmpty(Optional.empty())");
    }

    @Test
    void identityOwnsOnlyIdentityDbAndIdentityRedisNamespace() {
        String yaml = read("../linklife-identity-service/src/main/resources/application.yaml");
        assertThat(yaml).contains("linklife_identity");
        String constants = read("../linklife-identity-service/src/main/java/com/linklife/identity/redis/IdentityRedisConstants.java");
        assertThat(constants).contains("identity:login:token:")
                .contains("login:token:")
                .contains("identity:login:code:");
        String pom = read("../linklife-identity-service/pom.xml");
        assertThat(pom).doesNotContain("linklife-merchant-service")
                .doesNotContain("linklife-transaction-service")
                .doesNotContain("linklife-social-service");
        String controller = read("../linklife-identity-service/src/main/java/com/linklife/identity/internal/InternalUserController.java");
        assertThat(controller).contains("UserSummaryRequest").contains("List<UserSummaryDTO>");
        String dto = read("../linklife-common-core/src/main/java/com/linklife/common/core/user/UserSummaryDTO.java");
        assertThat(dto).contains("record UserSummaryDTO(Long id, String nickName, String icon)");
    }

    @Test
    void merchantOwnsOnlyMerchantDbAndMerchantRedisNamespace() {
        String yaml = read("../linklife-merchant-service/src/main/resources/application.yaml");
        assertThat(yaml).contains("linklife_merchant");
        String constants = read("../linklife-merchant-service/src/main/java/com/linklife/merchant/redis/MerchantRedisConstants.java");
        assertThat(constants).contains("merchant:cache:shop:")
                .contains("merchant:lock:shop:")
                .contains("merchant:shop:geo:");
        String upload = read("../linklife-merchant-service/src/main/java/com/linklife/merchant/config/UploadProperties.java");
        assertThat(upload).contains("normalize()").contains("toAbsolutePath()");
    }

    @Test
    void transactionOwnsOnlyTransactionDbAndNamespaceWithoutFeign() {
        String yaml = read("../linklife-transaction-service/src/main/resources/application.yaml");
        assertThat(yaml).contains("linklife_transaction");
        String constants = read("../linklife-transaction-service/src/main/java/com/linklife/trade/redis/TransactionRedisConstants.java");
        assertThat(constants).contains("transaction:seckill:stock:")
                .contains("transaction:order:submission:")
                .contains("transaction:stream.orders");
        String pom = read("../linklife-transaction-service/pom.xml");
        assertThat(pom).doesNotContain("openfeign")
                .doesNotContain("linklife-identity-service")
                .doesNotContain("linklife-merchant-service")
                .doesNotContain("linklife-social-service");
    }

    @Test
    void socialOwnsOnlySocialDbAndNamespaceWithFeignBatchOnly() {
        String yaml = read("../linklife-social-service/src/main/resources/application.yaml");
        assertThat(yaml).contains("linklife_social");
        String blogImpl = read("../linklife-social-service/src/main/java/com/linklife/social/service/impl/BlogServiceImpl.java");
        assertThat(blogImpl).contains("social:lock:blog:like:");
        String client = read("../linklife-social-service/src/main/java/com/linklife/social/client/IdentityUserClient.java");
        assertThat(client).contains("linklife-identity-service").contains("identityUserClient");
        String mapper = read("../linklife-social-service/src/main/java/com/linklife/social/mapper/BlogMapper.java");
        assertThat(mapper).contains("selectFollowFeed").contains("tb_follow").contains("tb_blog");
        String pom = read("../linklife-social-service/pom.xml");
        assertThat(pom).doesNotContain("linklife-identity-service")
                .doesNotContain("linklife-merchant-service")
                .doesNotContain("linklife-transaction-service");
    }

    @Test
    void commonModulesStayBoundaryClean() {
        String corePom = read("../linklife-common-core/pom.xml");
        assertThat(corePom).doesNotContain("starter-web")
                .doesNotContain("spring-boot-starter-data-redis")
                .doesNotContain("mybatis")
                .doesNotContain("openfeign");
        String webPom = read("../linklife-common-web/pom.xml");
        assertThat(webPom).doesNotContain("mybatis").doesNotContain("mysql");
        Path webEntity = Paths.get("../linklife-common-web/src/main/java/com/linklife/common/web/entity");
        Path webMapper = Paths.get("../linklife-common-web/src/main/java/com/linklife/common/web/mapper");
        assertThat(Files.exists(webEntity)).isFalse();
        assertThat(Files.exists(webMapper)).isFalse();
    }

    @Test
    void finalReactorHasNoMonolithAndNoSecondApplication() {
        Path monolith = Paths.get("../linklife-stage3-monolith");
        Path legacySrc = Paths.get("../src/main/java/com/linklife/LinkLifeApplication.java");
        assertThat(Files.exists(monolith)).isFalse();
        assertThat(Files.exists(legacySrc)).isFalse();
        String parentPom = read("../pom.xml");
        assertThat(parentPom).doesNotContain("linklife-stage3-monolith");
        for (String module : new String[]{"linklife-common-core", "linklife-common-web", "linklife-gateway",
                "linklife-identity-service", "linklife-merchant-service", "linklife-transaction-service",
                "linklife-social-service"}) {
            assertThat(parentPom).contains("<module>" + module + "</module>");
        }
    }
}
