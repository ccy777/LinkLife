package com.linklife.trade.redis;

/**
 * Transaction Redis namespace 契约（DB 0，全部 transaction:*；Stage 3 交易 Key 的正式迁移目标）。
 */
public class TransactionRedisConstants {

    public static final String SECKILL_STOCK_KEY = "transaction:seckill:stock:";
    public static final String SECKILL_ORDER_KEY = "transaction:seckill:order:";
    public static final String SECKILL_BEGIN_KEY = "transaction:seckill:begin:";
    public static final String SECKILL_END_KEY = "transaction:seckill:end:";
    public static final String SECKILL_INIT_MARKER_KEY_PREFIX = "transaction:seckill:init:marker:";

    public static final String ORDER_SUBMISSION_KEY_PREFIX = "transaction:order:submission:";
    public static final Long ORDER_SUBMISSION_TTL = 24 * 60 * 60L;
    public static final String ORDER_CLOSE_COMPENSATION_KEY_PREFIX = "transaction:order:close:comp:";
    public static final String ORDER_CREATE_COMPENSATION_KEY_PREFIX = "transaction:order:create:comp:";

    public static final String STREAM_ORDERS_KEY = "transaction:stream.orders";
    public static final String STREAM_ORDERS_DLQ_KEY = "transaction:stream.orders.dlq";
    public static final String STREAM_ORDERS_DLQ_WRITTEN_KEY = "transaction:stream.orders:dlq:written";
    public static final String STREAM_ORDERS_RETRY_KEY = "transaction:stream.orders:retry";

    public static final String LOCK_ORDER_KEY_PREFIX = "transaction:lock:order:";
    public static final String ICR_ORDER_KEY_PREFIX = "transaction:icr:order:";

    private TransactionRedisConstants() {
    }
}
