package com.linklife.identity.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Identity Redisson 配置（Stage 3 迁移）：复用 Spring Boot RedisProperties，single-server，DB 0；
 * 不打印 host/password/URL；非法值 fail-fast。
 */
@Configuration
public class RedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(RedisProperties redisProperties) {
        return Redisson.create(buildConfig(redisProperties));
    }

    static Config buildConfig(RedisProperties redisProperties) {
        String host = redisProperties.getHost();
        if (host != null) {
            host = host.trim();
        }
        int port = redisProperties.getPort();
        int database = redisProperties.getDatabase();
        if (host == null || host.isEmpty()) {
            throw new IllegalStateException("spring.data.redis.host 不能为空");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("spring.data.redis.port 非法");
        }
        if (database < 0) {
            throw new IllegalStateException("spring.data.redis.database 非法");
        }
        boolean ssl = redisProperties.getSsl() != null && redisProperties.getSsl().isEnabled();
        String scheme = ssl ? "rediss" : "redis";
        Config config = new Config();
        config.useSingleServer()
                .setAddress(scheme + "://" + host + ":" + port)
                .setDatabase(database);
        String password = redisProperties.getPassword();
        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }
        String username = redisProperties.getUsername();
        if (username != null && !username.isEmpty()) {
            config.useSingleServer().setUsername(username);
        }
        Duration timeout = redisProperties.getTimeout();
        if (timeout != null) {
            long millis = timeout.toMillis();
            if (millis <= 0L) {
                throw new IllegalStateException("spring.data.redis.timeout 非法");
            }
            if (millis > Integer.MAX_VALUE) {
                throw new IllegalStateException("spring.data.redis.timeout 过大");
            }
            config.useSingleServer().setConnectTimeout((int) millis);
            config.useSingleServer().setTimeout((int) millis);
        }
        return config;
    }
}
