package com.linklife.trade.db;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction schema 契约：仅 5 张表；保留全部交易索引；001 迁移归属；无跨库 FK。
 */
class TransactionSchemaContractTest {

    private String schema() throws Exception {
        return Files.readString(Paths.get("src/main/resources/db/schema.sql"));
    }

    @Test
    void containsOnlyTransactionTables() throws Exception {
        String sql = schema();
        assertThat(sql).contains("CREATE TABLE `tb_voucher`");
        assertThat(sql).contains("CREATE TABLE `tb_seckill_voucher`");
        assertThat(sql).contains("CREATE TABLE `tb_voucher_order`");
        assertThat(sql).contains("CREATE TABLE `tb_order_status_log`");
        assertThat(sql).contains("CREATE TABLE `tb_outbox_event`");
        for (String t : new String[]{"tb_user", "tb_user_info", "tb_shop", "tb_shop_type", "tb_blog",
                "tb_blog_like", "tb_blog_comments", "tb_follow"}) {
            assertThat(sql).as("不得包含 " + t).doesNotContain("CREATE TABLE `" + t + "`");
        }
    }

    @Test
    void keepsRequiredIndexes() throws Exception {
        String sql = schema();
        assertThat(sql).contains("uk_user_voucher");
        assertThat(sql).contains("idx_status_create_time_id");
        assertThat(sql).contains("uk_order_status_log_idem");
        assertThat(sql).contains("uk_order_status_log_transition");
        assertThat(sql).contains("idx_order_status_log_order");
        assertThat(sql).contains("uk_outbox_event_id");
        assertThat(sql).contains("uk_outbox_business_key");
        assertThat(sql).contains("idx_outbox_status_next_retry");
        assertThat(sql).contains("idx_outbox_status_locked_until");
    }

    @Test
    void noCrossDatabaseForeignKey() throws Exception {
        String sql = schema();
        assertThat(sql).doesNotContain("FOREIGN KEY");
        assertThat(sql).doesNotContain("REFERENCES");
    }

    @Test
    void oneTimeUpgradeMigrationBelongsToTransaction() throws Exception {
        String migration = Files.readString(Paths.get(
                "src/main/resources/db/upgrade/001_add_voucher_order_unique_constraint.sql"));
        assertThat(migration).contains("tb_voucher_order");
        assertThat(migration).contains("uk_user_voucher");
    }
}
