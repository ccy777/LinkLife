package com.linklife.integration.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ManualIntegrationEnvironment 两级隔离条件的纯函数测试：
 * 通过注入 Map 读取函数覆盖全部安全判定分支，不修改操作系统环境变量、不连接任何外部服务。
 */
class ManualIntegrationEnvironmentTest {

    private static final String ENABLED = ManualIntegrationEnvironment.ENABLED_VAR;
    private static final String CONFIRM = ManualIntegrationEnvironment.CONFIRM_ISOLATED_VAR;
    private static final String HOST = ManualIntegrationEnvironment.REDIS_HOST_VAR;
    private static final String PORT = ManualIntegrationEnvironment.REDIS_PORT_VAR;
    private static final String DATABASE = ManualIntegrationEnvironment.REDIS_DATABASE_VAR;
    private static final String DB_URL = ManualIntegrationEnvironment.DB_URL_VAR;
    private static final String DB_USERNAME = ManualIntegrationEnvironment.DB_USERNAME_VAR;

    private Map<String, String> baseRedis() {
        Map<String, String> env = new HashMap<>();
        env.put(ENABLED, "true");
        env.put(CONFIRM, "true");
        env.put(HOST, "127.0.0.1");
        env.put(PORT, "6379");
        env.put(DATABASE, "5");
        return env;
    }

    private Map<String, String> fullEnv() {
        Map<String, String> env = baseRedis();
        env.put(DB_URL, "jdbc:mysql://localhost:3306/linklife_stage1_test");
        env.put(DB_USERNAME, "manual_user");
        return env;
    }

    private Function<String, String> reader(Map<String, String> env) {
        return env::get;
    }

    @Test
    void notEnabledRejected() {
        Map<String, String> env = fullEnv();
        env.remove(ENABLED);

        assertThat(ManualIntegrationEnvironment.isRedisIsolated(reader(env))).isFalse();
        assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(env))).isFalse();
    }

    @Test
    void isolationNotConfirmedRejected() {
        Map<String, String> env = fullEnv();
        env.remove(CONFIRM);

        assertThat(ManualIntegrationEnvironment.isRedisIsolated(reader(env))).isFalse();
        assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(env))).isFalse();
    }

    @Test
    void hostMissingOrBlankRejected() {
        Map<String, String> missing = fullEnv();
        missing.remove(HOST);
        assertThat(ManualIntegrationEnvironment.isRedisIsolated(reader(missing))).isFalse();

        Map<String, String> blank = fullEnv();
        blank.put(HOST, "   ");
        assertThat(ManualIntegrationEnvironment.isRedisIsolated(reader(blank))).isFalse();
    }

    @Test
    void invalidPortRejected() {
        for (String port : new String[]{"0", "-1", "65536"}) {
            Map<String, String> env = fullEnv();
            env.put(PORT, port);

            assertThat(ManualIntegrationEnvironment.isRedisIsolated(reader(env)))
                    .as("port=%s 必须拒绝", port)
                    .isFalse();
        }
    }

    @Test
    void invalidDatabaseRejected() {
        for (String database : new String[]{"0", "-5"}) {
            Map<String, String> env = fullEnv();
            env.put(DATABASE, database);

            assertThat(ManualIntegrationEnvironment.isRedisIsolated(reader(env)))
                    .as("database=%s 必须拒绝", database)
                    .isFalse();
        }
    }

    @Test
    void positiveRedisDatabasePassesForRedisOnly() {
        assertThat(ManualIntegrationEnvironment.isRedisIsolated(reader(baseRedis()))).isTrue();
    }

    @Test
    void missingDbUrlRejectedForFullOnly() {
        Map<String, String> env = baseRedis();

        assertThat(ManualIntegrationEnvironment.isRedisIsolated(reader(env))).isTrue();
        assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(env))).isFalse();
    }

    @Test
    void forbiddenSchemasRejected() {
        for (String schema : new String[]{
                "mysql", "information_schema", "performance_schema", "sys"}) {
            Map<String, String> env = fullEnv();
            env.put(DB_URL, "jdbc:mysql://localhost:3306/" + schema);

            assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(env)))
                    .as("schema=%s 必须拒绝", schema)
                    .isFalse();
        }
    }

    @Test
    void schemaWithoutTestOrStage1Rejected() {
        Map<String, String> env = fullEnv();
        env.put(DB_URL, "jdbc:mysql://localhost:3306/legacy_app");

        assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(env))).isFalse();
    }

    @Test
    void nonJdbcMysqlUrlRejected() {
        Map<String, String> env = fullEnv();
        env.put(DB_URL, "postgresql://localhost:5432/linklife_stage1_test");

        assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(env))).isFalse();
    }

    @Test
    void linklifeStage1TestSchemaPasses() {
        assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(fullEnv()))).isTrue();
    }

    @Test
    void blankDbUsernameRejected() {
        Map<String, String> env = fullEnv();
        env.put(DB_USERNAME, "   ");

        assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(env))).isFalse();
    }

    @Test
    void redisOnlyConditionDoesNotRequireDbVariables() {
        assertThat(ManualIntegrationEnvironment.isRedisIsolated(reader(baseRedis()))).isTrue();
    }

    @Test
    void fullConditionRequiresAllVariables() {
        Map<String, String> env = baseRedis();
        assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(env))).isFalse();

        env.put(DB_URL, "jdbc:mysql://localhost:3306/linklife_stage1_test");
        assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(env)))
                .as("DB username 缺失时 Full 条件必须不满足")
                .isFalse();

        env.put(DB_USERNAME, "manual_user");
        assertThat(ManualIntegrationEnvironment.isFullIsolated(reader(env))).isTrue();
    }

    @Test
    void disabledReasonsAreStaticRuleTextWithoutSensitiveValues() throws Exception {
        String source = new String(Files.readAllBytes(
                Paths.get("src/test/java/com/linklife/integration/support/ManualIntegrationEnvironment.java")),
                StandardCharsets.UTF_8);

        // disabled 消息必须是静态规则文本常量，不得拼接环境变量值或完整 URL
        assertThat(source).contains("ConditionEvaluationResult.disabled(REDIS_DISABLED_REASON)");
        assertThat(source).contains("ConditionEvaluationResult.disabled(FULL_DISABLED_REASON)");
        assertThat(source).doesNotContain("disabled(\"\" +");
    }
}
