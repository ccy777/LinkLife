package com.linklife.social.db;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 017J-D schema 契约测试：tb_follow 唯一约束/反向索引、tb_blog_like 表与唯一约束/双索引、
 * 博客关注流索引、migration 去重/自关注清理/建表索引、schema.sql 与 migration 命名一致。
 */
class SocialSchemaContractTest {

    private String readSchema() throws Exception {
        return normalizeLineEndings(Files.readString(
                Paths.get("src/main/resources/db/schema.sql"), StandardCharsets.UTF_8));
    }

    private String readMigration() throws Exception {
        return normalizeLineEndings(Files.readString(
                Paths.get("src/main/resources/db/upgrade/002_social_consistency.sql"),
                StandardCharsets.UTF_8));
    }

    private static String normalizeLineEndings(String sql) {
        // 正式 source archive（git archive）在 Windows 上导出 zip 时文本文件为 CRLF，
        // 测试断言统一按 LF 归一化，保证仓库内与无 Git metadata 的源码包行为一致。
        return sql.replace("\r\n", "\n");
    }

    private String tableDdl(String sql, String table) {
        String marker = "CREATE TABLE `" + table + "`";
        int start = sql.indexOf(marker);
        assertThat(start).as("DDL 必须存在: %s", table).isGreaterThanOrEqualTo(0);
        int end = sql.indexOf("ENGINE = InnoDB", start);
        assertThat(end).as("DDL 必须完整: %s", table).isGreaterThan(start);
        return sql.substring(start, end);
    }

    @Test
    void followTableHasUniqueConstraintAndReverseIndex() throws Exception {
        String ddl = tableDdl(readSchema(), "tb_follow");
        assertThat(ddl).contains("UNIQUE KEY `uk_follow_user_target` (`user_id`, `follow_user_id`)");
        assertThat(ddl).contains("KEY `idx_follow_target_user` (`follow_user_id`, `user_id`)");
        assertThat(ddl).contains("PRIMARY KEY (`id`) USING BTREE");
    }

    @Test
    void blogLikeTableHasAllContractFieldsAndConstraints() throws Exception {
        String ddl = tableDdl(readSchema(), "tb_blog_like");
        for (String column : new String[]{
                "`id` bigint(20) NOT NULL AUTO_INCREMENT",
                "`blog_id` bigint(20) UNSIGNED NOT NULL",
                "`user_id` bigint(20) UNSIGNED NOT NULL",
                "`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP"}) {
            assertThat(ddl).contains(column);
        }
        assertThat(ddl).contains("PRIMARY KEY (`id`) USING BTREE");
        assertThat(ddl).contains("UNIQUE KEY `uk_blog_like` (`blog_id`, `user_id`)");
        assertThat(ddl).contains("KEY `idx_blog_like_blog_time` (`blog_id`, `create_time`, `id`)");
        assertThat(ddl).contains("KEY `idx_blog_like_user_time` (`user_id`, `create_time`, `id`)");
    }

    @Test
    void blogTableHasFollowFeedIndex() throws Exception {
        String ddl = tableDdl(readSchema(), "tb_blog");
        assertThat(ddl).contains("KEY `idx_blog_user_create_id` (`user_id`, `create_time`, `id`)");
    }

    @Test
    void migrationCleansSelfFollowAndDedupes() throws Exception {
        String sql = readMigration();
        assertThat(sql).contains("user_id` = `follow_user_id");
        assertThat(sql).contains("DELETE f1 FROM `tb_follow` f1");
        assertThat(sql).contains("AND f1.`id` > f2.`id`");
    }

    @Test
    void migrationAddsFollowConstraintsAndIndexes() throws Exception {
        String sql = readMigration();
        assertThat(sql).contains("ADD UNIQUE KEY `uk_follow_user_target` (`user_id`, `follow_user_id`)");
        assertThat(sql).contains("ADD KEY `idx_follow_target_user` (`follow_user_id`, `user_id`)");
    }

    @Test
    void migrationCreatesBlogLikeTableAndBlogIndex() throws Exception {
        String sql = readMigration();
        assertThat(sql).contains("CREATE TABLE `tb_blog_like`");
        assertThat(sql).contains("UNIQUE KEY `uk_blog_like` (`blog_id`, `user_id`)");
        assertThat(sql).contains("KEY `idx_blog_like_blog_time` (`blog_id`, `create_time`, `id`)");
        assertThat(sql).contains("KEY `idx_blog_like_user_time` (`user_id`, `create_time`, `id`)");
        assertThat(sql).contains("ADD KEY `idx_blog_user_create_id` (`user_id`, `create_time`, `id`)");
    }

    @Test
    void legacyMonolithAndMigrationUseIdenticalNames() throws Exception {
        String schema = readSchema();
        String migration = readMigration();
        for (String name : new String[]{
                "uk_follow_user_target",
                "idx_follow_target_user",
                "tb_blog_like",
                "uk_blog_like",
                "idx_blog_like_blog_time",
                "idx_blog_like_user_time",
                "idx_blog_user_create_id"}) {
        assertThat(schema).contains(name);
            assertThat(migration).contains(name);
        }
    }

    @Test
    void migrationIsOneShotAndNotDisguisedAsRepeatable() throws Exception {
        String migration = readMigration();
        assertThat(migration)
                .contains("一次性")
                .contains("只应");
        assertThat(migration).doesNotContain("CREATE TABLE IF NOT EXISTS");
    }

    @Test
    void legacyMonolithZeroesSeedLikedAfterAllBlogInserts() throws Exception {
        String sql = readSchema();
        String update = "UPDATE `tb_blog` SET `liked` = 0;";
        assertThat(sql).contains(update);
        assertThat(sql).contains("Stage 3 以 tb_blog_like 为点赞事实源；全新 seed 不保留无法对应用户明细的历史聚合点赞数。");
        int updatePos = sql.indexOf(update);
        int lastSeedInsertPos = sql.lastIndexOf("INSERT INTO `tb_blog` VALUES");
        assertThat(updatePos).isGreaterThan(lastSeedInsertPos);
        assertThat(sql).doesNotContain("UPDATE `tb_blog` SET `comments`");
    }

    @Test
    void migrationZeroesLegacyLikedAfterCreatingBlogLike() throws Exception {
        String sql = readMigration();
        String update = "UPDATE `tb_blog`\nSET `liked` = 0\nWHERE `liked` IS NULL OR `liked` <> 0;";
        assertThat(sql).contains(update);
        int updatePos = sql.indexOf("UPDATE `tb_blog`");
        int createPos = sql.indexOf("CREATE TABLE `tb_blog_like`");
        assertThat(updatePos).isGreaterThan(createPos);
        assertThat(sql).doesNotContain("DELETE FROM `tb_blog`");
        assertThat(sql).doesNotContain("UPDATE `tb_blog` SET `comments`");
    }

    @Test
    void migrationExplainsHistoricalIdentityNotRecoverable() throws Exception {
        String sql = readMigration();
        assertThat(sql)
                .contains("旧版本点赞用户身份只存在于 Redis ZSet")
                .contains("不扫描旧 Redis")
                .contains("无法仅凭聚合 liked 恢复用户级明细")
                .contains("将旧 aggregate 重置为 0");
    }

    @Test
    void noFakeBlogLikeRowsAreInserted() throws Exception {
        assertThat(readSchema()).doesNotContain("INSERT INTO `tb_blog_like`");
        assertThat(readMigration()).doesNotContain("INSERT INTO `tb_blog_like`");
    }
}
