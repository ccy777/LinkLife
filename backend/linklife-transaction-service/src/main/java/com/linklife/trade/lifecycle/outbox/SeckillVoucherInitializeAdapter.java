package com.linklife.trade.lifecycle.outbox;

import com.linklife.trade.redis.TransactionRedisConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 秒杀券 Redis 原子初始化适配器：独占执行 {@code seckill-voucher-initialize.lua} 并解释整数返回码。
 *
 * <p>Key 只由服务端根据经过校验的命令构造（stock/begin/end/init-marker），不接受客户端 Key；
 * 不操作一人一券 Set 或 Stream；不执行第二次非 Lua 初始化。</p>
 *
 * <p>映射：INITIALIZED/ALREADY_INITIALIZED → success；安全回滚码与 {@link DataAccessException} → retryable；
 * 参数/类型/冲突/损坏/回滚失败、null 与未知码 → fatal；禁止 Long 窄化。</p>
 */
@Component
public class SeckillVoucherInitializeAdapter {

    private static final Logger log = LoggerFactory.getLogger(SeckillVoucherInitializeAdapter.class);

    private static final DateTimeFormatter HANDLED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    static final DefaultRedisScript<Long> INITIALIZE_SCRIPT;

    static {
        INITIALIZE_SCRIPT = new DefaultRedisScript<>();
        INITIALIZE_SCRIPT.setLocation(new ClassPathResource("seckill-voucher-initialize.lua"));
        INITIALIZE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public SeckillVoucherInitializeResult initialize(SeckillVoucherInitializeCommand command) {
        String stockKey = TransactionRedisConstants.SECKILL_STOCK_KEY + command.voucherId();
        String beginKey = TransactionRedisConstants.SECKILL_BEGIN_KEY + command.voucherId();
        String endKey = TransactionRedisConstants.SECKILL_END_KEY + command.voucherId();
        String markerKey = TransactionRedisConstants.SECKILL_INIT_MARKER_KEY_PREFIX + command.voucherId();
        try {
            Long result = stringRedisTemplate.execute(
                    INITIALIZE_SCRIPT,
                    List.of(stockKey, beginKey, endKey, markerKey),
                    String.valueOf(command.voucherId()),
                    String.valueOf(command.initialStock()),
                    String.valueOf(command.beginEpochMillis()),
                    String.valueOf(command.endEpochMillis()),
                    command.eventId(),
                    command.businessKey(),
                    command.handledAt().format(HANDLED_AT_FORMATTER),
                    String.valueOf(command.eventVersion()));
            return mapResult(result);
        } catch (DataAccessException e) {
            log.warn("Redis 秒杀券初始化访问异常，按可重试处理 voucherId={}", command.voucherId());
            return SeckillVoucherInitializeResult.retryable("SECKILL_INIT_ACCESS_FAILED");
        }
    }

    private SeckillVoucherInitializeResult mapResult(Long raw) {
        if (raw == null) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_NULL_RESULT");
        }
        long code = raw.longValue();
        if (code == 0L || code == 1L) {
            return SeckillVoucherInitializeResult.success();
        }
        if (code == 20L) {
            return SeckillVoucherInitializeResult.retryable("SECKILL_INIT_WRITE_ROLLED_BACK");
        }
        if (code == 10L) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_INVALID_ARGUMENT");
        }
        if (code == 11L) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_STOCK_KEY_TYPE_INVALID");
        }
        if (code == 12L) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_BEGIN_KEY_TYPE_INVALID");
        }
        if (code == 13L) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_END_KEY_TYPE_INVALID");
        }
        if (code == 14L) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_MARKER_KEY_TYPE_INVALID");
        }
        if (code == 15L) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_MARKER_CORRUPT");
        }
        if (code == 16L) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_MARKER_IDENTITY_CONFLICT");
        }
        if (code == 17L) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_STATE_CORRUPT");
        }
        if (code == 18L) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_PREEXISTING_STATE_CONFLICT");
        }
        if (code == 21L) {
            return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_WRITE_ROLLBACK_FAILED");
        }
        return SeckillVoucherInitializeResult.fatal("SECKILL_INIT_UNKNOWN_CODE");
    }
}
