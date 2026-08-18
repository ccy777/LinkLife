package com.linklife.trade.submission;

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
 * 订单创建失败 Redis 补偿适配器：独占执行 {@code order-create-failure-compensation.lua} 并解释整数返回码。
 *
 * <p>Key 只由服务端根据经过校验的命令构造（stockKey=transaction:seckill:stock:{voucherId}、
 * qualificationKey=transaction:seckill:order:{voucherId}、markerKey=transaction:order:create:comp:{orderId}），
 * 不接受客户端 Key；不执行第二次非 Lua 补偿。</p>
 *
 * <p>映射：APPLIED/ALREADY_APPLIED → success；可重试脚本码与 {@link DataAccessException} → retryable；
 * 致命脚本码、null 与未知返回码 → fatal；不把 Redis 异常吞成成功。</p>
 */
@Component
public class OrderCreateFailureCompensationAdapter {

    private static final Logger log = LoggerFactory.getLogger(OrderCreateFailureCompensationAdapter.class);

    private static final DateTimeFormatter HANDLED_AT_FORMATTER =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss");

    static final DefaultRedisScript<Long> COMPENSATION_SCRIPT;

    static {
        COMPENSATION_SCRIPT = new DefaultRedisScript<>();
        COMPENSATION_SCRIPT.setLocation(new ClassPathResource("order-create-failure-compensation.lua"));
        COMPENSATION_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 执行订单创建失败 Redis 幂等补偿。
     *
     * @param command 经过校验的补偿命令（handledAt 秒级）
     * @return 补偿结果
     */
    public OrderCreateFailureCompensationResult compensate(OrderCreateFailureCompensationCommand command) {
        String stockKey = TransactionRedisConstants.SECKILL_STOCK_KEY + command.voucherId();
        String qualificationKey = TransactionRedisConstants.SECKILL_ORDER_KEY + command.voucherId();
        String markerKey = TransactionRedisConstants.ORDER_CREATE_COMPENSATION_KEY_PREFIX + command.orderId();
        try {
            Long result = stringRedisTemplate.execute(
                    COMPENSATION_SCRIPT,
                    List.of(stockKey, qualificationKey, markerKey),
                    String.valueOf(command.orderId()),
                    String.valueOf(command.userId()),
                    String.valueOf(command.voucherId()),
                    command.mode().name(),
                    String.valueOf(command.existingOrderId()),
                    command.handledAt().format(HANDLED_AT_FORMATTER),
                    String.valueOf(command.version()));
            return mapResult(result);
        } catch (DataAccessException e) {
            log.warn("Redis 订单创建失败补偿访问异常，按可重试处理 orderId={}", command.orderId());
            return OrderCreateFailureCompensationResult.retryable("CREATE_COMP_ACCESS_FAILED");
        }
    }

    private OrderCreateFailureCompensationResult mapResult(Long raw) {
        if (raw == null) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_NULL_RESULT");
        }
        long code = raw.longValue();
        if (code == 0L || code == 1L) {
            return OrderCreateFailureCompensationResult.success();
        }
        if (code == 20L) {
            return OrderCreateFailureCompensationResult.retryable("CREATE_COMP_STOCK_INCREMENT_FAILED");
        }
        if (code == 21L) {
            return OrderCreateFailureCompensationResult.retryable("CREATE_COMP_QUALIFICATION_REMOVE_ROLLED_BACK");
        }
        if (code == 23L) {
            return OrderCreateFailureCompensationResult.retryable("CREATE_COMP_MARKER_WRITE_ROLLED_BACK");
        }
        if (code == 10L) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_INVALID_ARGUMENT");
        }
        if (code == 11L) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_STOCK_KEY_MISSING");
        }
        if (code == 12L) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_STOCK_KEY_TYPE_INVALID");
        }
        if (code == 13L) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_STOCK_VALUE_INVALID");
        }
        if (code == 14L) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_QUALIFICATION_KEY_TYPE_INVALID");
        }
        if (code == 15L) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_MARKER_KEY_TYPE_INVALID");
        }
        if (code == 16L) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_MARKER_CORRUPT");
        }
        if (code == 17L) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_MARKER_IDENTITY_CONFLICT");
        }
        if (code == 22L) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_QUALIFICATION_REMOVE_ROLLBACK_FAILED");
        }
        if (code == 24L) {
            return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_MARKER_WRITE_ROLLBACK_FAILED");
        }
        return OrderCreateFailureCompensationResult.fatal("CREATE_COMP_UNKNOWN_CODE");
    }
}
