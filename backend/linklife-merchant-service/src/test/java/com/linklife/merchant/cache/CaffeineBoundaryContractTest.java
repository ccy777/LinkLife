package com.linklife.merchant.cache;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage5B 边界：Caffeine 只允许出现在 merchant-service，且不使用 spring-boot-starter-cache。
 */
class CaffeineBoundaryContractTest {

    @Test
    void caffeineDependencyOnlyInMerchantModule() throws Exception {
        String merchantPom = Files.readString(Paths.get("pom.xml"));
        assertThat(merchantPom).contains("com.github.ben-manes.caffeine");
        assertThat(merchantPom).doesNotContain("spring-boot-starter-cache");

        for (String module : new String[]{
                "linklife-common-core", "linklife-common-web", "linklife-gateway",
                "linklife-identity-service", "linklife-transaction-service",
                "linklife-social-service"}) {
            String pom = Files.readString(Paths.get("../" + module + "/pom.xml"));
            assertThat(pom)
                    .as(module + " 不得引入 caffeine")
                    .doesNotContain("ben-manes.caffeine")
                    .doesNotContain("spring-boot-starter-cache");
        }
    }

    @Test
    void noCaffeineImportOutsideMerchantMainSources() throws Exception {
        for (String module : new String[]{
                "linklife-common-core", "linklife-common-web", "linklife-gateway",
                "linklife-identity-service", "linklife-transaction-service",
                "linklife-social-service"}) {
            Path src = Paths.get("../" + module + "/src/main/java");
            if (!Files.exists(src)) {
                continue;
            }
            try (var stream = Files.walk(src)) {
                stream.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        String source = Files.readString(p);
                        assertThat(source)
                                .as(p.toString())
                                .doesNotContain("import com.github.benmanes.caffeine.");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }
}
