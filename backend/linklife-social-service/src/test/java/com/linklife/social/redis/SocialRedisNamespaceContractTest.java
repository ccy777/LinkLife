package com.linklife.social.redis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Social Redis namespace：生产源码不得包含旧 Social Redis 前缀执行常量；
 * 新锁 namespace 为 social:lock:blog:like:。
 */
class SocialRedisNamespaceContractTest {

    private static final List<String> LEGACY = List.of(
            "follows:", "blog:liked:", "feed:", "lock:blog:like:");

    @Test
    void mainSourcesHaveNoLegacySocialRedisPrefixes() throws Exception {
        try (Stream<Path> paths = Files.walk(Paths.get("src/main/java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String source = Files.readString(p);
                    for (String literal : LEGACY) {
                        String pattern = "lock:blog:like:".equals(literal)
                                ? "(?<!social:)" + Pattern.quote(literal)
                                : Pattern.quote(literal);
                        assertThat(Pattern.compile(pattern).matcher(source).find())
                                .as(p + " contains legacy " + literal)
                                .isFalse();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void likeLockUsesSocialNamespace() throws Exception {
        String source = Files.readString(Paths.get(
                "src/main/java/com/linklife/social/service/impl/BlogServiceImpl.java"));
        assertThat(source).contains("social:lock:blog:like:");
    }
}
