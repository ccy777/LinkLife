package com.linklife.trade.db;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时扫描复合索引的 schema 契约测试：
 * idx_status_payment_due_at_id 唯一、列顺序严格 status/payment_due_at/id、
 * 主键与 uk_user_voucher 保留、payment_due_at 非空且无重复索引定义。
 */
class OrderTimeoutSchemaContractTest {

    @Test
    void timeoutIndexExistsExactlyOnceInVoucherOrderTable() throws Exception {
        String ddl = voucherOrderDdl();

        assertThat(ddl)
                .contains("KEY `idx_status_payment_due_at_id` (`status`, `payment_due_at`, `id`) USING BTREE");
        assertThat(countOccurrences(ddl, "idx_status_payment_due_at_id")).isEqualTo(1);
    }

    @Test
    void indexColumnOrderIsStrictlyStatusPaymentDueAtId() throws Exception {
        String ddl = voucherOrderDdl();
        String index = "`idx_status_payment_due_at_id` (`status`, `payment_due_at`, `id`)";

        assertThat(ddl).contains(index);
        assertThat(ddl).doesNotContain("`idx_status_payment_due_at_id` (`payment_due_at`, `status`, `id`)");
        assertThat(ddl).doesNotContain("`idx_status_payment_due_at_id` (`id`, `payment_due_at`, `status`)");
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

        assertThat(countOccurrences(sql, "idx_status_payment_due_at_id")).isEqualTo(1);
    }

    @Test
    void columnTypesAndDefaultsAreUnchanged() throws Exception {
        String ddl = voucherOrderDdl();

        assertThat(ddl).contains("`status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1");
        assertThat(ddl).contains("`create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下单时间'");
        assertThat(ddl).contains("`payment_due_at` timestamp NOT NULL COMMENT '订单创建时冻结的支付到期绝对时刻（秒级精度）'");
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
