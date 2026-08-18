package com.linklife.trade.redis;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Transaction Redis namespace 契约：常量全部 transaction:*；
 * 生产 Java 与 Lua 不得出现旧交易 Key 字面量。
 */
class TransactionRedisNamespaceContractTest {

    private static final List<String> LEGACY_LITERALS = List.of(
            "seckill:stock:", "seckill:order:", "seckill:begin:", "seckill:end:",
            "seckill:init:marker:", "order:submission:", "order:close:comp:",
            "order:create:comp:", "stream.orders", "icr:", "lock:order:");

    @Test
    void constantsUseTransactionPrefix() {
        assertThat(TransactionRedisConstants.SECKILL_STOCK_KEY).isEqualTo("transaction:seckill:stock:");
        assertThat(TransactionRedisConstants.SECKILL_ORDER_KEY).isEqualTo("transaction:seckill:order:");
        assertThat(TransactionRedisConstants.SECKILL_BEGIN_KEY).isEqualTo("transaction:seckill:begin:");
        assertThat(TransactionRedisConstants.SECKILL_END_KEY).isEqualTo("transaction:seckill:end:");
        assertThat(TransactionRedisConstants.SECKILL_INIT_MARKER_KEY_PREFIX)
                .isEqualTo("transaction:seckill:init:marker:");
        assertThat(TransactionRedisConstants.ORDER_SUBMISSION_KEY_PREFIX)
                .isEqualTo("transaction:order:submission:");
        assertThat(TransactionRedisConstants.ORDER_CLOSE_COMPENSATION_KEY_PREFIX)
                .isEqualTo("transaction:order:close:comp:");
        assertThat(TransactionRedisConstants.ORDER_CREATE_COMPENSATION_KEY_PREFIX)
                .isEqualTo("transaction:order:create:comp:");
        assertThat(TransactionRedisConstants.STREAM_ORDERS_KEY).isEqualTo("transaction:stream.orders");
        assertThat(TransactionRedisConstants.STREAM_ORDERS_DLQ_KEY).isEqualTo("transaction:stream.orders.dlq");
        assertThat(TransactionRedisConstants.STREAM_ORDERS_DLQ_WRITTEN_KEY)
                .isEqualTo("transaction:stream.orders:dlq:written");
        assertThat(TransactionRedisConstants.STREAM_ORDERS_RETRY_KEY).isEqualTo("transaction:stream.orders:retry");
        assertThat(TransactionRedisConstants.LOCK_ORDER_KEY_PREFIX).isEqualTo("transaction:lock:order:");
        assertThat(TransactionRedisConstants.ICR_ORDER_KEY_PREFIX).isEqualTo("transaction:icr:order:");
    }

    @Test
    void mainJavaSourcesHaveNoLegacyTransactionKeyLiterals() throws Exception {
        List<Pattern> patterns = legacyPatterns();
        try (Stream<Path> paths = Files.walk(Paths.get("src/main/java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    String source = Files.readString(p);
                    for (Pattern pattern : patterns) {
                        assertThat(pattern.matcher(source).find())
                                .as(p + " contains legacy transaction key " + pattern)
                                .isFalse();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    @Test
    void luaFilesHaveNoLegacyTransactionKeyLiterals() throws Exception {
        List<Pattern> patterns = legacyPatterns();
        for (String lua : List.of("seckill.lua", "order-submission-transition.lua",
                "order-close-compensation.lua", "order-create-failure-compensation.lua",
                "seckill-voucher-initialize.lua")) {
            String content = Files.readString(Paths.get("src/main/resources/" + lua));
            for (Pattern pattern : patterns) {
                assertThat(pattern.matcher(content).find())
                        .as(lua + " contains legacy transaction key " + pattern)
                        .isFalse();
            }
        }
    }

    private static List<Pattern> legacyPatterns() {
        // 负向后瞻：排除 transaction: 前缀，避免把 transaction:seckill:stock: 误判为旧字面量
        return LEGACY_LITERALS.stream()
                .map(l -> Pattern.compile("(?<!transaction:)" + Pattern.quote(l)))
                .toList();
    }

    @Test
    void luaHardcodedNewPrefixes() throws Exception {
        String seckill = Files.readString(Paths.get("src/main/resources/seckill.lua"));
        assertThat(seckill).contains("'transaction:seckill:stock:'");
        assertThat(seckill).contains("'transaction:seckill:order:'");
        assertThat(seckill).contains("'transaction:seckill:begin:'");
        assertThat(seckill).contains("'transaction:seckill:end:'");
        assertThat(seckill).contains("'transaction:stream.orders'");
        assertThat(seckill).contains("'transaction:order:submission:'");
        String transition = Files.readString(Paths.get("src/main/resources/order-submission-transition.lua"));
        assertThat(transition).contains("'transaction:order:submission:'");
    }
}
