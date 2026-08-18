package com.linklife.transaction.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 锁定本轮 RocketMQ 只属于 Transaction timeout，且不替换 Redis Stream。 */
class RocketMqModuleBoundaryContractTest {

    private static final Path BACKEND = Path.of("..").toAbsolutePath().normalize();
    private static final List<String> BUSINESS_MODULES = List.of(
            "linklife-identity-service",
            "linklife-merchant-service",
            "linklife-transaction-service",
            "linklife-social-service");

    @Test
    void onlyTransactionBusinessModuleDeclaresRocketMqClient() throws Exception {
        for (String module : BUSINESS_MODULES) {
            String pom = Files.readString(BACKEND.resolve(module).resolve("pom.xml"));
            if (module.equals("linklife-transaction-service")) {
                assertThat(pom).contains("<artifactId>rocketmq-client-java</artifactId>");
            } else {
                assertThat(pom).doesNotContain("<artifactId>rocketmq-client-java</artifactId>");
            }
        }
    }

    @Test
    void nonTransactionBusinessSourcesDoNotImportRocketMq() throws Exception {
        for (String module : BUSINESS_MODULES) {
            if (module.equals("linklife-transaction-service")) continue;
            Path sourceRoot = BACKEND.resolve(module).resolve("src/main");
            try (Stream<Path> files = Files.walk(sourceRoot)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    assertThat(Files.readString(file))
                            .as("RocketMQ 不得越界进入 %s: %s", module, file)
                            .doesNotContain("org.apache.rocketmq");
                }
            }
        }
    }

    @Test
    void transactionDoesNotDependOnOtherBusinessServicesAndStreamRemains() throws Exception {
        String pom = Files.readString(BACKEND.resolve("linklife-transaction-service/pom.xml"));
        assertThat(pom)
                .doesNotContain("<artifactId>linklife-identity-service</artifactId>")
                .doesNotContain("<artifactId>linklife-merchant-service</artifactId>")
                .doesNotContain("<artifactId>linklife-social-service</artifactId>");

        Path transaction = BACKEND.resolve("linklife-transaction-service");
        assertThat(transaction.resolve(
                "src/main/java/com/linklife/trade/messaging/OrderStreamConsumer.java")).exists();
        assertThat(Files.readString(transaction.resolve("src/main/resources/seckill.lua")))
                .contains("xadd", "stream.orders");
    }
}
