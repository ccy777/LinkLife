package com.linklife.gateway.audit;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFileAttributeView;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage4-R1（stage4-v1.1）静态 contract 测试：锁定 Merchant 上传卷、
 * Redis 数据卷/AOF、Redis CLI 密码不进 argv、migration 私有临时目录与日志脱敏、
 * README 可移植执行方式与 migration 脚本在 source archive 中的可移植性。
 *
 * <p>纯文件静态审计（不启动 Spring 上下文），工作目录为 gateway module 根。</p>
 */
class Stage4PersistenceMigrationSafetyContractTest {

    private static String read(String first, String... more) {
        Path p = Paths.get(first, more);
        try {
            return Files.readString(p).replace("\r\n", "\n");
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 " + p.toAbsolutePath(), e);
        }
    }

    @Test
    void merchantServicePersistsUploadsInNamedVolume() {
        String compose = read("../deploy/docker-compose.stage4.yml");
        assertThat(compose)
                .contains("merchant_uploads:/var/lib/linklife/uploads")
                .contains("LINKLIFE_UPLOAD_ROOT: /var/lib/linklife/uploads")
                .contains("name: linklife-stage4_merchant_uploads");
        int merchantSection = compose.indexOf("merchant-service:");
        int volumesSection = compose.lastIndexOf("volumes:");
        assertThat(merchantSection).isGreaterThan(-1);
        assertThat(volumesSection).isGreaterThan(-1);
        assertThat(compose.indexOf("merchant_uploads:/var/lib/linklife/uploads"))
                .as("merchant upload mount 必须位于 merchant-service 定义内")
                .isGreaterThan(merchantSection)
                .isLessThan(volumesSection);
    }

    @Test
    void redisPersistsDataVolumeAndUsesAclSafeHealthcheck() {
        String compose = read("../deploy/docker-compose.stage4.yml");
        assertThat(compose)
                .contains("redis_data:/data")
                .contains("name: linklife-stage4_redis_data")
                .contains("REDISCLI_AUTH=\\\"$${REDIS_ADMIN_PASSWORD}\\\" redis-cli --no-auth-warning ping")
                .doesNotContain("redis-cli -a ");
        int redisSection = compose.indexOf("  redis:");
        int volumesSection = compose.lastIndexOf("volumes:");
        assertThat(compose.indexOf("redis_data:/data"))
                .as("redis 数据卷挂载必须位于 redis 服务定义内")
                .isGreaterThan(redisSection)
                .isLessThan(volumesSection);
    }

    @Test
    void redisStartScriptEnablesAofWithEverysecFsync() {
        String script = read("../deploy/redis/start-redis-with-acl.sh");
        assertThat(script)
                .contains("--dir /data")
                .contains("--appendonly yes")
                .contains("--appendfsync everysec")
                .doesNotContain("--appendonly no");
    }

    @Test
    void redisMigrationUsesPrivateTempDirAndRedactedLogs() {
        String script = read("../deploy/migration/migrate-stage3-redis.sh");
        assertThat(script)
                .contains("umask 077")
                .contains("mktemp -d")
                .contains("rm -rf -- \"${WORKDIR}\"")
                .contains("trap 'cleanup' EXIT")
                .contains("trap 'exit 1' INT TERM")
                .contains("REDISCLI_AUTH=\"${LINKLIFE_REDIS_ADMIN_PASSWORD}\" redis-cli")
                .doesNotContain("-a \"${LINKLIFE_REDIS_ADMIN_PASSWORD}\"");
        // 生产日志禁止直接回显 raw key / target key。
        assertThat(script)
                .doesNotContain("active legacy lock ${key}")
                .doesNotContain("no mapping for ${key}")
                .doesNotContain("target already exists: ${dst}")
                .doesNotContain("RENAMENX ${src} -> ${dst}")
                .doesNotContain("postflight ${dst}");
    }

    @Test
    void readmeUsesPortableShMigrationInvocation() {
        String readme = read("../README.md");
        assertThat(readme)
                .contains("sh backend/deploy/migration/migrate-stage3-mysql.sh")
                .contains("sh backend/deploy/migration/migrate-stage3-redis.sh");
    }

    @Test
    void migrationScriptsRemainPortableInSourceArchive() {
        Path mysqlMigration = Paths.get("..", "deploy", "migration", "migrate-stage3-mysql.sh");
        Path redisMigration = Paths.get("..", "deploy", "migration", "migrate-stage3-redis.sh");
        // 两个 migration 文件必须存在于 source archive 中（不依赖 Git metadata）。
        assertThat(mysqlMigration).exists();
        assertThat(redisMigration).exists();
        // 均以可移植 shebang 开头。
        assertThat(read("../deploy/migration/migrate-stage3-mysql.sh"))
                .startsWith("#!/usr/bin/env sh");
        assertThat(read("../deploy/migration/migrate-stage3-redis.sh"))
                .startsWith("#!/usr/bin/env sh");
        // README 仍以显式 `sh` 调用保证可移植执行（Windows 等非 POSIX 文件系统也可运行）。
        assertThat(read("../README.md"))
                .contains("sh backend/deploy/migration/migrate-stage3-mysql.sh")
                .contains("sh backend/deploy/migration/migrate-stage3-redis.sh");
        // POSIX 文件系统下额外校验 execute permission；非 POSIX（如 Windows）不得因此失败。
        boolean posix = Files.getFileAttributeView(mysqlMigration, PosixFileAttributeView.class) != null
                && Files.getFileAttributeView(redisMigration, PosixFileAttributeView.class) != null;
        if (posix) {
            assertThat(Files.isExecutable(mysqlMigration))
                    .as("mysql migration 脚本在 POSIX 文件系统上必须可执行")
                    .isTrue();
            assertThat(Files.isExecutable(redisMigration))
                    .as("redis migration 脚本在 POSIX 文件系统上必须可执行")
                    .isTrue();
        }
    }
}
