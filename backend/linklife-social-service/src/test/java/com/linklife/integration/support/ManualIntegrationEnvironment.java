package com.linklife.integration.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * 手工集成环境辅助：只读取任务书明确允许的 9 个环境变量，
 * 提供两级类级执行条件（在测试实例与 Spring 上下文创建前评估）：
 * {@link RedisIsolationRequired}（Redis-only 测试）与 {@link FullIsolationRequired}（完整基础设施测试）。
 * 安全判定提取为可注入环境读取函数（{@link Function}）的纯函数，便于单元测试，无需修改操作系统环境变量。
 * 本类与所有手工集成测试均不打印任何环境变量值（host、password、完整 URL 等）；
 * 判定失败原因只含规则描述，不含具体值。
 */
public final class ManualIntegrationEnvironment {

    public static final String ENABLED_VAR = "LINKLIFE_MANUAL_INTEGRATION_ENABLED";
    public static final String CONFIRM_ISOLATED_VAR = "LINKLIFE_MANUAL_CONFIRM_ISOLATED";
    public static final String REDIS_HOST_VAR = "LINKLIFE_MANUAL_REDIS_HOST";
    public static final String REDIS_PORT_VAR = "LINKLIFE_MANUAL_REDIS_PORT";
    public static final String REDIS_PASSWORD_VAR = "LINKLIFE_MANUAL_REDIS_PASSWORD";
    public static final String REDIS_DATABASE_VAR = "LINKLIFE_MANUAL_REDIS_DATABASE";
    public static final String DB_URL_VAR = "LINKLIFE_MANUAL_DB_URL";
    public static final String DB_USERNAME_VAR = "LINKLIFE_MANUAL_DB_USERNAME";
    public static final String DB_PASSWORD_VAR = "LINKLIFE_MANUAL_DB_PASSWORD";

    private static final Set<String> FORBIDDEN_SCHEMAS = new HashSet<>(Arrays.asList(
            "mysql", "information_schema", "performance_schema", "sys"));

    static final String REDIS_DISABLED_REASON =
            "隔离条件不满足：LINKLIFE_MANUAL_INTEGRATION_ENABLED/CONFIRM_ISOLATED 未确认，"
                    + "或 Redis host 缺失/空白、port 不在 1-65535、database 不大于 0，"
                    + "拒绝创建上下文与连接外部服务";

    static final String FULL_DISABLED_REASON =
            "隔离条件不满足：Redis 隔离条件未满足，或 DB URL 缺失/非 jdbc:mysql:// 开头、"
                    + "schema 不含 test/stage1、schema 为禁止名称（mysql/information_schema/"
                    + "performance_schema/sys）、或 DB username 空白，拒绝创建上下文与连接外部服务";

    private ManualIntegrationEnvironment() {
    }

    // ---------------- 纯函数安全判定（可注入环境读取函数） ----------------

    static boolean switchesConfirmed(Function<String, String> env) {
        return parseTrue(env, ENABLED_VAR) && parseTrue(env, CONFIRM_ISOLATED_VAR);
    }

    static boolean redisConnectionValid(Function<String, String> env) {
        String host = env.apply(REDIS_HOST_VAR);
        if (host == null || host.trim().isEmpty()) {
            return false;
        }
        int port = parseInt(env, REDIS_PORT_VAR);
        if (port < 1 || port > 65535) {
            return false;
        }
        int database = parseInt(env, REDIS_DATABASE_VAR);
        return database > 0;
    }

    /**
     * Redis-only 隔离条件：两个开关为 true + host 非空 + port 1-65535 + database &gt; 0。
     * 不要求任何 MySQL 变量，允许用户先只验证 Redis。
     */
    public static boolean isRedisIsolated(Function<String, String> env) {
        return switchesConfirmed(env) && redisConnectionValid(env);
    }

    /**
     * 完整基础设施隔离条件：Redis 条件全部满足，且 DB URL 指向含 test/stage1 的测试 schema、
     * username 非空。
     */
    public static boolean isFullIsolated(Function<String, String> env) {
        return isRedisIsolated(env) && dbConnectionValid(env);
    }

    static boolean dbConnectionValid(Function<String, String> env) {
        String url = env.apply(DB_URL_VAR);
        if (url == null) {
            return false;
        }
        String trimmedUrl = url.trim();
        if (!trimmedUrl.startsWith("jdbc:mysql://")) {
            return false;
        }
        String schema = extractSchema(trimmedUrl);
        if (schema == null) {
            return false;
        }
        if (!schema.matches("(?i).*(test|stage1).*")) {
            return false;
        }
        if (FORBIDDEN_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String username = env.apply(DB_USERNAME_VAR);
        return username != null && !username.trim().isEmpty();
    }

    /**
     * 从 JDBC URL 提取 schema 名称（不含完整 URL）。
     */
    static String extractSchema(String url) {
        if (url == null) {
            return null;
        }
        String base = url;
        int q = url.indexOf('?');
        if (q >= 0) {
            base = url.substring(0, q);
        }
        int slash = base.lastIndexOf('/');
        if (slash < 0 || slash == base.length() - 1) {
            return null;
        }
        String schema = base.substring(slash + 1);
        return schema.isEmpty() ? null : schema;
    }

    // ---------------- 便捷读取（trim 后；空白密码视为 null） ----------------

    public static boolean isEnabled() {
        return parseTrue(System::getenv, ENABLED_VAR);
    }

    public static boolean isIsolatedConfirmed() {
        return parseTrue(System::getenv, CONFIRM_ISOLATED_VAR);
    }

    public static String redisHost() {
        return trimToNull(System.getenv(REDIS_HOST_VAR));
    }

    public static int redisPort() {
        return parseInt(System::getenv, REDIS_PORT_VAR);
    }

    /**
     * 密码：null 或 trim 后空白时返回 null（客户端不得调用 setPassword）；非空时返回 trim 后值。
     * 返回值只用于连接，不打印。
     */
    public static String redisPassword() {
        return emptyToNull(System.getenv(REDIS_PASSWORD_VAR));
    }

    public static int redisDatabase() {
        return parseInt(System::getenv, REDIS_DATABASE_VAR);
    }

    public static String dbUrl() {
        return trimToNull(System.getenv(DB_URL_VAR));
    }

    public static String dbUsername() {
        return trimToNull(System.getenv(DB_USERNAME_VAR));
    }

    public static String dbPassword() {
        return emptyToNull(System.getenv(DB_PASSWORD_VAR));
    }

    public static String dbSchemaName() {
        return extractSchema(dbUrl());
    }

    // ---------------- 类级执行条件 ----------------

    /**
     * Redis-only 测试类级条件：{@link #isRedisIsolated(Function)} 在上下文/客户端创建前评估。
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @ExtendWith(RedisIsolationCondition.class)
    public @interface RedisIsolationRequired {
    }

    /**
     * 完整基础设施测试类级条件：{@link #isFullIsolated(Function)} 在上下文创建前评估。
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @ExtendWith(FullIsolationCondition.class)
    public @interface FullIsolationRequired {
    }

    public static final class RedisIsolationCondition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (isRedisIsolated(System::getenv)) {
                return ConditionEvaluationResult.enabled(
                        "Redis 隔离条件满足：开关确认、host 非空、port 1-65535、database > 0");
            }
            return ConditionEvaluationResult.disabled(REDIS_DISABLED_REASON);
        }
    }

    public static final class FullIsolationCondition implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            if (isFullIsolated(System::getenv)) {
                return ConditionEvaluationResult.enabled(
                        "完整基础设施隔离条件满足：Redis 隔离 + DB URL 指向含 test/stage1 的测试 schema");
            }
            return ConditionEvaluationResult.disabled(FULL_DISABLED_REASON);
        }
    }

    private static boolean parseTrue(Function<String, String> env, String name) {
        String raw = env.apply(name);
        return raw != null && "true".equalsIgnoreCase(raw.trim());
    }

    private static int parseInt(Function<String, String> env, String name) {
        String raw = env.apply(name);
        if (raw == null || raw.trim().isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String trimToNull(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String emptyToNull(String raw) {
        return trimToNull(raw);
    }
}
