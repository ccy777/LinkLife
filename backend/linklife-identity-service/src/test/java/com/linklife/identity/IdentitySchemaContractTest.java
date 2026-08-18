package com.linklife.identity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identity schema 契约：仅 tb_user / tb_user_info；保留 phone 唯一索引与 seed；
 * 无跨库 FK；不含其他 9 张表。
 */
class IdentitySchemaContractTest {

    private String schema() throws Exception {
        return Files.readString(Paths.get("src/main/resources/db/schema.sql"));
    }

    @Test
    void containsOnlyIdentityTables() throws Exception {
        String sql = schema();
        assertThat(sql).contains("CREATE TABLE `tb_user`");
        assertThat(sql).contains("CREATE TABLE `tb_user_info`");
        assertThat(sql).doesNotContain("tb_shop");
        assertThat(sql).doesNotContain("tb_shop_type");
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
    void keepsPhoneUniqueIndexAndSeed() throws Exception {
        String sql = schema();
        assertThat(sql).contains("UNIQUE INDEX `uniqe_key_phone`");
        assertThat(sql).contains("INSERT INTO `tb_user` VALUES (1, '13686869696'");
        assertThat(sql).contains("INSERT INTO `tb_user` VALUES (2, '13838411438'");
    }

    @Test
    void noCrossDatabaseForeignKey() throws Exception {
        String sql = schema();
        assertThat(sql).doesNotContain("FOREIGN KEY");
        assertThat(sql).doesNotContain("REFERENCES");
    }
}
