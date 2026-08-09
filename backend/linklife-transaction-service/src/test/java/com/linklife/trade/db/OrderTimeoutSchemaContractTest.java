package com.linklife.trade.db;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时扫描复合索引的 schema 契约测试：
 * idx_status_create_time_id 唯一、列顺序严格 status/create_time/id、
 * 主键与 uk_user_voucher 保留、无重复索引定义、列类型与默认值不变。
 */
class OrderTimeoutSchemaContractTest {

    @Test
    void timeoutIndexExistsExactlyOnceInVoucherOrderTable() throws Exception {
        String ddl = voucherOrderDdl();

        assertThat(ddl)
                .contains("KEY `idx_status_create_time_id` (`status`, `create_time`, `id`) USING BTREE");
        assertThat(countOccurrences(ddl, "idx_status_create_time_id")).isEqualTo(1);
    }

    @Test
    void indexColumnOrderIsStrictlyStatusCreateTimeId() throws Exception {
        String ddl = voucherOrderDdl();
        String index = "`idx_status_create_time_id` (`status`, `create_time`, `id`)";

        assertThat(ddl).contains(index);
        assertThat(ddl).doesNotContain("`idx_status_create_time_id` (`create_time`, `status`, `id`)");
        assertThat(ddl).doesNotContain("`idx_status_create_time_id` (`id`, `create_time`, `status`)");
    }

    @Test
    void primaryKeyAndUniqueConstraintArePreserved() throws Exception {
        String ddl = voucherOrderDdl();

        assertThat(ddl).contains("PRIMARY KEY (`id`) USING BTREE");
        assertThat(ddl).contains("UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`) USING BTREE");
    }

    @Test
    void noDuplicateIndexDefinitionInWholeFile() throws Exception {
        String sql = readSql();

        assertThat(countOccurrences(sql, "idx_status_create_time_id")).isEqualTo(1);
    }

    @Test
    void columnTypesAndDefaultsAreUnchanged() throws Exception {
        String ddl = voucherOrderDdl();

        assertThat(ddl).contains("`status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1");
        assertThat(ddl).contains("`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间'");
        assertThat(ddl).contains("`update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP");
    }

    private String voucherOrderDdl() throws Exception {
        String sql = readSql();
        int start = sql.indexOf("CREATE TABLE `tb_voucher_order`");
        assertThat(start).as("schema.sql 必须包含 tb_voucher_order 建表语句").isGreaterThanOrEqualTo(0);
        int end = sql.indexOf("ENGINE = InnoDB", start);
        assertThat(end).as("tb_voucher_order 建表语句必须以 ENGINE = InnoDB 结束").isGreaterThan(start);
        return sql.substring(start, end);
    }

    private String readSql() throws Exception {
        return new String(Files.readAllBytes(
        Paths.get("src/main/resources/db/schema.sql")), StandardCharsets.UTF_8);
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }
}
