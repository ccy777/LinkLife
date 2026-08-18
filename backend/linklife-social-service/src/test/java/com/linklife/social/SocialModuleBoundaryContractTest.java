package com.linklife.social;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Social module boundary：不依赖 identity/merchant/transaction module；无 UserHolder/UserDTO/IUserService import。
 */
class SocialModuleBoundaryContractTest {

    @Test
    void mainSourcesNeverImportIdentityImplementationOrUserHolder() throws Exception {
        try (Stream<Path> paths = Files.walk(Paths.get("src/main/java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String source = Files.readString(p);
                    assertThat(source)
                            .as(p.toString())
                            .doesNotContain("import com.linklife.identity.")
                            .doesNotContain("import com.linklife.merchant.")
                            .doesNotContain("import com.linklife.transaction.")
                            .doesNotContain("com.linklife.identity.security.UserHolder")
                            .doesNotContain("com.linklife.identity.dto.UserDTO")
                            .doesNotContain("com.linklife.identity.service.IUserService");
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
