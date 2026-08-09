package com.linklife.merchant;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Merchant schema 契约：仅 tb_shop / tb_shop_type；保留 shop type 索引与 seed；
 * 无跨库 FK；不含其他 11 张表。
 */
class MerchantSchemaContractTest {

    private String schema() throws Exception {
        return Files.readString(Paths.get("src/main/resources/db/schema.sql"));
    }

    @Test
    void containsOnlyMerchantTables() throws Exception {
        String sql = schema();
        assertThat(sql).contains("CREATE TABLE `tb_shop`");
        assertThat(sql).contains("CREATE TABLE `tb_shop_type`");
        assertThat(sql).doesNotContain("tb_user");
        assertThat(sql).doesNotContain("tb_user_info");
        assertThat(sql).doesNotContain("tb_blog");
        assertThat(sql).doesNotContain("tb_blog_like");
        assertThat(sql).doesNotContain("tb_blog_comments");
        assertThat(sql).doesNotContain("tb_follow");
        assertThat(sql).doesNotContain("tb_voucher");
        assertThat(sql).doesNotContain("tb_seckill_voucher");
        assertThat(sql).doesNotContain("tb_voucher_order");
        assertThat(sql).doesNotContain("tb_order_status_log");
        assertThat(sql).doesNotContain("tb_outbox_event");
    }

    @Test
    void keepsTypeIndexAndSeeds() throws Exception {
        String sql = schema();
        assertThat(sql).contains("INDEX `foreign_key_type`(`type_id`)");
        assertThat(sql).contains("INSERT INTO `tb_shop` VALUES (1, '拾光咖啡'");
        assertThat(sql).contains("INSERT INTO `tb_shop_type` VALUES (1, '美食'");
    }

    @Test
    void noCrossDatabaseForeignKey() throws Exception {
        String sql = schema();
        assertThat(sql).doesNotContain("FOREIGN KEY");
        assertThat(sql).doesNotContain("REFERENCES");
    }
}
