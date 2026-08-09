package com.linklife.identity.config;

import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Redisson 外置配置测试：不创建真实 RedissonClient，不连接 Redis。
 */
class RedissonConfigTest {

    /**
     * Redisson 3.13.6 的 {@link Config#getSingleServerConfig()} 为包私有方法，
     * 通过反射读取已构建的配置，不创建真实 RedissonClient、不连接 Redis。
     */
    private static SingleServerConfig singleServer(Config config) throws Exception {
        java.lang.reflect.Method method = Config.class.getDeclaredMethod("getSingleServerConfig");
        method.setAccessible(true);
        return (SingleServerConfig) method.invoke(config);
    }

    private RedisProperties props(String host, int port, int database) {
        RedisProperties properties = new RedisProperties();
        properties.setHost(host);
        properties.setPort(port);
        properties.setDatabase(database);
        return properties;
    }

    @Test
    void hostAndPortMapped() throws Exception {
        SingleServerConfig config = singleServer(RedissonConfig.buildConfig(props("localhost", 6380, 0)));

        assertThat(config.getAddress()).isEqualTo("redis://localhost:6380");
    }

    @Test
    void hostIsTrimmedBeforeAddressMapping() throws Exception {
        SingleServerConfig config = singleServer(RedissonConfig.buildConfig(props("  localhost  ", 6380, 0)));

        assertThat(config.getAddress()).isEqualTo("redis://localhost:6380");
    }

    @Test
    void blankHostRejected() {
        assertThatThrownBy(() -> RedissonConfig.buildConfig(props("   ", 6379, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host");
        assertThatThrownBy(() -> RedissonConfig.buildConfig(props("", 6379, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host");
    }

    @Test
    void sourceSupportsOnlySingleServerInThisStage() throws Exception {
        String source = new String(Files.readAllBytes(
        Paths.get("src/main/java/com/linklife/identity/config/RedissonConfig.java")), StandardCharsets.UTF_8);

        assertThat(source).contains("useSingleServer()");
        assertThat(source).doesNotContain("useSentinelServers");
        assertThat(source).doesNotContain("useClusterServers");
        // 不打印 host、password、URL 等配置值
        assertThat(source).doesNotContain("log.");
    }

    @Test
    void databaseMapped() throws Exception {
        SingleServerConfig config = singleServer(RedissonConfig.buildConfig(props("localhost", 6379, 3)));

        assertThat(config.getDatabase()).isEqualTo(3);
    }

    @Test
    void emptyPasswordNotSet() throws Exception {
        RedisProperties properties = props("localhost", 6379, 0);

        assertThat(singleServer(RedissonConfig.buildConfig(properties)).getPassword()).isNull();
    }

    @Test
    void nonEmptyPasswordSet() throws Exception {
        RedisProperties properties = props("localhost", 6379, 0);
        properties.setPassword("secret");

        assertThat(singleServer(RedissonConfig.buildConfig(properties)).getPassword()).isEqualTo("secret");
    }

    @Test
    void sslSchemeMapped() throws Exception {
        RedisProperties properties = props("localhost", 6380, 0);
        properties.getSsl().setEnabled(true);

        assertThat(singleServer(RedissonConfig.buildConfig(properties)).getAddress())
                .isEqualTo("rediss://localhost:6380");
    }

    @Test
    void timeoutMapped() throws Exception {
        RedisProperties properties = props("localhost", 6379, 0);
        properties.setTimeout(Duration.ofMillis(3000));

        SingleServerConfig config = singleServer(RedissonConfig.buildConfig(properties));
        assertThat(config.getConnectTimeout()).isEqualTo(3000);
        assertThat(config.getTimeout()).isEqualTo(3000);
    }

    @Test
    void invalidPortFails() {
        assertThatThrownBy(() -> RedissonConfig.buildConfig(props("localhost", 0, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port");
        assertThatThrownBy(() -> RedissonConfig.buildConfig(props("localhost", 70000, 0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("port");
    }

    @Test
    void invalidDatabaseFails() {
        assertThatThrownBy(() -> RedissonConfig.buildConfig(props("localhost", 6379, -1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("database");
    }

    @Test
    void invalidTimeoutFails() {
        RedisProperties properties = props("localhost", 6379, 0);
        properties.setTimeout(Duration.ZERO);

        assertThatThrownBy(() -> RedissonConfig.buildConfig(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void destroyMethodContract() throws Exception {
        Bean bean = RedissonConfig.class.getDeclaredMethod("redissonClient", RedisProperties.class)
                .getAnnotation(Bean.class);

        assertThat(bean.destroyMethod()).isEqualTo("shutdown");
    }

    @Test
    void sourceHasNoHardcodedRedisAddress() throws Exception {
        String source = new String(Files.readAllBytes(
        Paths.get("src/main/java/com/linklife/identity/config/RedissonConfig.java")), StandardCharsets.UTF_8);

        assertThat(source).doesNotContain("127.0.0.1:6379");
    }
}
