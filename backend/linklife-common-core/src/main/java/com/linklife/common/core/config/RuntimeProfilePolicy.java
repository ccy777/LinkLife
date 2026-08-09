package com.linklife.common.core.config;

import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 生产 profile 统一判断：prod / production 均视为生产，忽略大小写。
 * 禁止使用 contains("prod")，避免 product-demo 等被误判。
 */
public final class RuntimeProfilePolicy {

    private static final Set<String> PRODUCTION_PROFILES =
            new HashSet<>(Arrays.asList("prod", "production"));

    private RuntimeProfilePolicy() {
    }

    public static boolean isProductionProfile(String[] activeProfiles) {
        if (activeProfiles == null) {
            return false;
        }
        for (String profile : activeProfiles) {
            if (profile != null && PRODUCTION_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isProductionProfile(Environment environment) {
        return environment != null && isProductionProfile(environment.getActiveProfiles());
    }
}
