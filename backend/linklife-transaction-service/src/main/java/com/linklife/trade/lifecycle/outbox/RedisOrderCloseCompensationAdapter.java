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
 * Redis 订单关闭库存补偿适配器：独占执行 {@code order-close-compensation.lua} 并解释整数返回码。
 *
 * <p>Key 只由服务端根据经过校验的 payload 构造（stockKey=transaction:seckill:stock:{voucherId}、
 * markerKey=transaction:order:close:comp:{orderId}），不接受客户端 Key；不执行 SREM、不执行第二次非 Lua 补偿。</p>
 *
 * <p>映射：APPLIED/ALREADY_APPLIED → success；可重试脚本码与 {@link DataAccessException} → retryable；
 * 致命脚本码、null 与未知返回码 → fatal；不把 Redis 异常吞成成功。</p>
 */
@Component
public class RedisOrderCloseCompensationAdapter {

    private static final Logger log = LoggerFactory.getLogger(RedisOrderCloseCompensationAdapter.class);

    /**
     * handledAt 固定秒级字符串格式：uuuu-MM-dd'T'HH:mm:ss。
     * 秒为零时也必须输出 :ss，不得依赖 LocalDateTime.toString() 的省略行为。
     */
    private static final DateTimeFormatter HANDLED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    static final DefaultRedisScript<Long> COMPENSATION_SCRIPT;

    static {
        COMPENSATION_SCRIPT = new DefaultRedisScript<>();
        COMPENSATION_SCRIPT.setLocation(new ClassPathResource("order-close-compensation.lua"));
        COMPENSATION_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 执行订单关闭 Redis 库存幂等补偿。
     *
     * @param command 经过校验的补偿命令（handledAt 秒级）
     * @return 补偿结果
     */
    public OrderCloseCompensationResult compensate(OrderCloseCompensationCommand command) {
        String stockKey = TransactionRedisConstants.SECKILL_STOCK_KEY + command.voucherId();
        String markerKey = TransactionRedisConstants.ORDER_CLOSE_COMPENSATION_KEY_PREFIX + command.orderId();
        try {
            Long result = stringRedisTemplate.execute(
                    COMPENSATION_SCRIPT,
                    List.of(stockKey, markerKey),
                    String.valueOf(command.orderId()),
                    String.valueOf(command.userId()),
                    String.valueOf(command.voucherId()),
                    command.eventId(),
                    command.businessKey(),
                    command.handledAt().format(HANDLED_AT_FORMATTER),
                    String.valueOf(command.eventVersion()));
            return mapResult(result);
        } catch (DataAccessException e) {
            log.warn("Redis 订单关闭补偿访问异常，按可重试处理 orderId={}", command.orderId());
            return OrderCloseCompensationResult.retryable("REDIS_COMPENSATION_ACCESS_FAILED");
        }
    }

    private OrderCloseCompensationResult mapResult(Long raw) {
        if (raw == null) {
            return OrderCloseCompensationResult.fatal("REDIS_COMPENSATION_NULL_RESULT");
        }
        // 必须按完整 long 值精确比较：先转 int/short/byte 会窄化，
        // 例如 4294967296L.intValue()==0、4294967297L.intValue()==1，会把未知码误判成成功。
        long code = raw.longValue();
        if (code == 0L || code == 1L) {
            return OrderCloseCompensationResult.success();
        }
        if (code == 20L) {
            return OrderCloseCompensationResult.retryable("REDIS_STOCK_INCREMENT_FAILED");
        }
        if (code == 21L) {
            return OrderCloseCompensationResult.retryable("REDIS_MARKER_WRITE_ROLLED_BACK");
        }
        if (code == 10L) {
            return OrderCloseCompensationResult.fatal("REDIS_INVALID_ARGUMENT");
        }
        if (code == 11L) {
            return OrderCloseCompensationResult.fatal("REDIS_STOCK_KEY_MISSING");
        }
        if (code == 12L) {
            return OrderCloseCompensationResult.fatal("REDIS_STOCK_KEY_TYPE_INVALID");
        }
        if (code == 13L) {
            return OrderCloseCompensationResult.fatal("REDIS_STOCK_VALUE_INVALID");
        }
        if (code == 14L) {
            return OrderCloseCompensationResult.fatal("REDIS_MARKER_KEY_TYPE_INVALID");
        }
        if (code == 15L) {
            return OrderCloseCompensationResult.fatal("REDIS_MARKER_CORRUPT");
        }
        if (code == 16L) {
            return OrderCloseCompensationResult.fatal("REDIS_MARKER_IDENTITY_CONFLICT");
        }
        if (code == 22L) {
            return OrderCloseCompensationResult.fatal("REDIS_MARKER_WRITE_ROLLBACK_FAILED");
        }
        return OrderCloseCompensationResult.fatal("REDIS_UNKNOWN_CODE");
    }
}
