package com.linklife.transaction.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceApplicationContractTest {

    @Test
    void applicationNameAndPortFrozen() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("app", new ClassPathResource("application.yaml"));
        PropertySource<?> source = sources.get(0);

        assertThat(source.getProperty("spring.application.name")).isEqualTo("linklife-transaction-service");
        assertThat(source.getProperty("server.port")).isEqualTo(8083);
        assertThat(source.getProperty("spring.cloud.nacos.discovery.register-enabled")).isEqualTo(Boolean.TRUE);
        assertThat(source.getProperty("spring.cloud.nacos.discovery.server-addr"))
                .asString()
                .startsWith("${NACOS_ADDR:");
    }
}
