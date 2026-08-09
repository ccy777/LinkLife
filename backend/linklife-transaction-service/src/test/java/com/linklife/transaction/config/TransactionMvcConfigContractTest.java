package com.linklife.transaction.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction Admin 注册契约：只注册 POST /voucher 与 POST /voucher/seckill。
 */
class TransactionMvcConfigContractTest {

    @Test
    void adminInterceptorRegisteredForVoucherPaths() throws Exception {
        String source = Files.readString(Paths.get(
                "src/main/java/com/linklife/transaction/config/TransactionMvcConfig.java"));
        assertThat(source).contains("new AdminMutationInterceptor(adminAuthorizationProperties)");
        assertThat(source).contains(".addPathPatterns(\"/voucher\", \"/voucher/seckill\")");
        assertThat(source).doesNotContain("addPathPatterns(\"/shop");
    }
}
