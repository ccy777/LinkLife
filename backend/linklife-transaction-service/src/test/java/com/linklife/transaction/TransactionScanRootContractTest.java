package com.linklife.transaction;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction 扫描根契约：显式 scanBasePackages 覆盖 transaction/promotion/trade/shared；
 * MapperScan 覆盖 promotion.mapper + trade.mapper；promotion/trade 核心类型存在且在同一 module。
 */
class TransactionScanRootContractTest {

    @Test
    void applicationScansPromotionTradeTransactionShared() {
        SpringBootApplication app = TransactionServiceApplication.class.getAnnotation(SpringBootApplication.class);
        assertThat(app).isNotNull();
        assertThat(app.scanBasePackages())
                .containsExactlyInAnyOrder("com.linklife.transaction", "com.linklife.promotion",
                        "com.linklife.trade", "com.linklife.shared");
    }

    @Test
    void mapperScanCoversPromotionAndTrade() {
        MapperScan mapperScan = TransactionServiceApplication.class.getAnnotation(MapperScan.class);
        assertThat(mapperScan).isNotNull();
        assertThat(mapperScan.value())
                .containsExactlyInAnyOrder("com.linklife.promotion.mapper", "com.linklife.trade.mapper");
    }

    @Test
    void promotionAndTradeTypesPresentInSameModule() throws Exception {
        for (String name : new String[]{
                "com.linklife.promotion.controller.VoucherController",
                "com.linklife.promotion.service.impl.VoucherServiceImpl",
                "com.linklife.promotion.mapper.VoucherMapper",
                "com.linklife.trade.controller.VoucherOrderController",
                "com.linklife.trade.application.OrderLifecycleService",
                "com.linklife.trade.messaging.OrderStreamConsumer",
                "com.linklife.trade.lifecycle.outbox.OutboxEventRouter",
                "com.linklife.trade.mapper.VoucherOrderMapper"
        }) {
            Class<?> clazz = Class.forName(name);
            assertThat(clazz.getPackageName()).startsWith("com.linklife.");
        }
    }
}
