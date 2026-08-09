package com.linklife.common.core.contract;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 4 reactor 结构契约：最终 parent + 7 child modules；
 * transitional monolith 已删除（018F）。
 */
class ModuleStructureContractTest {

    @Test
    void reactorModuleDirectoriesExist() {
        Path backend = Paths.get("..").toAbsolutePath().normalize();
        assertThat(backend.resolve("pom.xml")).exists();
        assertThat(backend.resolve("linklife-common-core")).isDirectory();
        assertThat(backend.resolve("linklife-common-web")).isDirectory();
        assertThat(backend.resolve("linklife-gateway")).isDirectory();
        assertThat(backend.resolve("linklife-identity-service")).isDirectory();
        assertThat(backend.resolve("linklife-merchant-service")).isDirectory();
        assertThat(backend.resolve("linklife-transaction-service")).isDirectory();
        assertThat(backend.resolve("linklife-social-service")).isDirectory();
        assertThat(backend.resolve("linklife-stage3-monolith")).doesNotExist();
    }

    @Test
    void parentPomIsPackagingPomWithFullModuleList() throws Exception {
        String pom = Files.readString(Paths.get("../pom.xml"));
        assertThat(pom).contains("<packaging>pom</packaging>");
        assertThat(pom).contains("<module>linklife-common-core</module>");
        assertThat(pom).contains("<module>linklife-common-web</module>");
        assertThat(pom).contains("<module>linklife-gateway</module>");
        assertThat(pom).contains("<module>linklife-identity-service</module>");
        assertThat(pom).contains("<module>linklife-merchant-service</module>");
        assertThat(pom).contains("<module>linklife-transaction-service</module>");
        assertThat(pom).contains("<module>linklife-social-service</module>");
        assertThat(pom).doesNotContain("linklife-stage3-monolith");
    }

    @Test
    void parentPomFreezesTargetVersions() throws Exception {
        String pom = Files.readString(Paths.get("../pom.xml"));
        assertThat(pom).contains("<version>3.5.15</version>");
        assertThat(pom).contains("<spring-cloud.version>2025.0.3</spring-cloud.version>");
        assertThat(pom).contains("<spring-cloud-alibaba.version>2025.0.0.0</spring-cloud-alibaba.version>");
        assertThat(pom).contains("<mybatis-plus.version>3.5.17</mybatis-plus.version>");
        assertThat(pom).contains("<redisson.version>3.13.6</redisson.version>");
        assertThat(pom).contains("<hutool.version>5.8.47</hutool.version>");
        assertThat(pom).doesNotContain("<hutool.version>5.7.17</hutool.version>");
    }
}
