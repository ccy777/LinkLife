package com.linklife.trade.db;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stage 3E（017C）schema 契约测试：
 * tb_order_status_log 与 tb_outbox_event 各定义一次、关键字段与可空性、双唯一约束、
 * 扫描索引列顺序、既有表与索引保留、状态码与列定义未变。
 */
class OrderCloseSchemaContractTest {

    @Test
    void bothTablesAreDefinedExactlyOnce() throws Exception {
        String sql = readSql();

        assertThat(countOccurrences(sql, "CREATE TABLE `tb_order_status_log`")).isEqualTo(1);
        assertThat(countOccurrences(sql, "CREATE TABLE `tb_outbox_event`")).isEqualTo(1);
        assertThat(countOccurrences(sql, "DROP TABLE IF EXISTS `tb_order_status_log`")).isEqualTo(1);
        assertThat(countOccurrences(sql, "DROP TABLE IF EXISTS `tb_outbox_event`")).isEqualTo(1);
    }

    @Test
    void statusLogTableHasAllContractFieldsAndConstraints() throws Exception {
        String ddl = tableDdl("tb_order_status_log");

        for (String column : new String[]{
                "`id` bigint(20) NOT NULL AUTO_INCREMENT",
                "`order_id` bigint(20) NOT NULL",
                "`from_status` tinyint(1) UNSIGNED NOT NULL",
                "`to_status` tinyint(1) UNSIGNED NOT NULL",
                "`trigger_type` varchar(32) NOT NULL",
                "`operator_type` varchar(16) NOT NULL",
                "`operator_id` bigint(20) NULL DEFAULT NULL",
                "`reason_code` varchar(32) NOT NULL",
                "`reason_detail` varchar(200) NULL DEFAULT NULL",
                "`idempotency_key` varchar(64) NOT NULL",
                "`created_time` datetime NOT NULL"}) {
            assertThat(ddl).contains(column);
        }
        assertThat(ddl).contains("PRIMARY KEY (`id`) USING BTREE");
        assertThat(ddl).contains("UNIQUE KEY `uk_order_status_log_idem` (`idempotency_key`) USING BTREE");
        assertThat(ddl)
                .contains("UNIQUE KEY `uk_order_status_log_transition` (`order_id`, `from_status`, `to_status`) USING BTREE");
        assertThat(ddl).contains("KEY `idx_order_status_log_order` (`order_id`) USING BTREE");
    }

    @Test
    void outboxTableHasAllContractFieldsAndConstraints() throws Exception {
        String ddl = tableDdl("tb_outbox_event");

        for (String column : new String[]{
                "`id` bigint(20) NOT NULL AUTO_INCREMENT",
                "`event_id` varchar(64) NOT NULL",
                "`business_key` varchar(96) NOT NULL",
                "`aggregate_type` varchar(32) NOT NULL",
                "`aggregate_id` bigint(20) NOT NULL",
                "`event_type` varchar(32) NOT NULL",
                "`event_version` int(11) NOT NULL",
                "`payload` varchar(1024) NOT NULL",
                "`status` varchar(16) NOT NULL DEFAULT 'PENDING'",
                "`retry_count` int(11) NOT NULL DEFAULT 0",
                "`next_retry_time` datetime NOT NULL",
                "`last_error_code` varchar(64) NULL DEFAULT NULL",
                "`created_time` datetime NOT NULL",
                "`updated_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP"}) {
            assertThat(ddl).contains(column);
        }
        assertThat(ddl).contains("UNIQUE KEY `uk_outbox_event_id` (`event_id`) USING BTREE");
        assertThat(ddl).contains("UNIQUE KEY `uk_outbox_business_key` (`business_key`) USING BTREE");
        assertThat(ddl)
                .contains("KEY `idx_outbox_status_next_retry` (`status`, `next_retry_time`) USING BTREE");
        assertThat(ddl)
                .contains("KEY `idx_outbox_status_locked_until` (`status`, `locked_until`) USING BTREE");
    }

    @Test
    void outboxLeaseFieldsAreNullable() throws Exception {
        String ddl = tableDdl("tb_outbox_event");

        assertThat(ddl).contains("`lock_token` varchar(64) NULL DEFAULT NULL");
        assertThat(ddl).contains("`locked_until` datetime NULL DEFAULT NULL");
        assertThat(ddl).contains("`processing_started_time` datetime NULL DEFAULT NULL");
        assertThat(ddl).contains("`completed_time` datetime NULL DEFAULT NULL");
    }

    @Test
    void existingTablesIndexesAndStatusCodesArePreserved() throws Exception {
        String sql = readSql();
        String orderDdl = tableDdl("tb_voucher_order");

        assertThat(orderDdl).contains("PRIMARY KEY (`id`) USING BTREE");
        assertThat(orderDdl).contains("UNIQUE KEY `uk_user_voucher` (`user_id`, `voucher_id`) USING BTREE");
        assertThat(orderDdl).contains("KEY `idx_status_payment_due_at_id` (`status`, `payment_due_at`, `id`) USING BTREE");
        assertThat(orderDdl)
                .contains("`status` tinyint(1) UNSIGNED NOT NULL DEFAULT 1 COMMENT '订单状态，1：未支付；2：已支付；3：已核销；4：已取消；5：退款中；6：已退款'");
        assertThat(sql).doesNotContain("DROP TABLE IF EXISTS `tb_voucher_order`\n");
    }

    @Test
    void noDuplicateTableOrIndexNames() throws Exception {
        String sql = readSql();

        assertThat(countOccurrences(sql, "`tb_order_status_log`")).isEqualTo(2);
        assertThat(countOccurrences(sql, "`tb_outbox_event`")).isEqualTo(2);
        assertThat(countOccurrences(sql, "uk_order_status_log_idem")).isEqualTo(1);
        assertThat(countOccurrences(sql, "uk_order_status_log_transition")).isEqualTo(1);
        assertThat(countOccurrences(sql, "uk_outbox_event_id")).isEqualTo(1);
        assertThat(countOccurrences(sql, "uk_outbox_business_key")).isEqualTo(1);
    }

    private String tableDdl(String table) throws Exception {
        String sql = readSql();
        int start = sql.indexOf("CREATE TABLE `" + table + "`");
        assertThat(start).as("schema.sql 必须包含 " + table + " 建表语句").isGreaterThanOrEqualTo(0);
        int end = sql.indexOf("ENGINE = InnoDB", start);
        assertThat(end).as(table + " 建表语句必须以 ENGINE = InnoDB 结束").isGreaterThan(start);
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
