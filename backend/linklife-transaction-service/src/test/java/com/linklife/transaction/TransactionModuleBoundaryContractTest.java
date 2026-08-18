package com.linklife.transaction;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction module boundary：不依赖 identity/merchant/social module；无 UserHolder；
 * 生产代码不引用 Gateway/common-web 业务包。
 */
class TransactionModuleBoundaryContractTest {

    @Test
    void mainSourcesNeverImportIdentityMerchantSocial() throws Exception {
        try (Stream<Path> paths = Files.walk(Paths.get("src/main/java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String source = Files.readString(p);
                    assertThat(source)
                            .as(p.toString())
                            .doesNotContain("import com.linklife.identity.")
                            .doesNotContain("import com.linklife.merchant.")
                            .doesNotContain("import com.linklife.social.")
                            .doesNotContain("com.linklife.identity.security.UserHolder");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void mainSourcesDoNotReferenceGatewayOrCommonWebBusinessPackages() throws Exception {
        try (Stream<Path> paths = Files.walk(Paths.get("src/main/java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String source = Files.readString(p);
                    assertThat(source).as(p.toString()).doesNotContain("import com.linklife.gateway.");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
