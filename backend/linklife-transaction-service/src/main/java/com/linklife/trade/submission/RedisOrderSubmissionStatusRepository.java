package com.linklife.trade.submission;

import com.linklife.trade.redis.TransactionRedisConstants;
import com.linklife.trade.entity.VoucherOrder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Redis 订单提交状态仓储：通过原子 Lua 脚本完成状态转换与身份保护，
 * 只依赖 StringRedisTemplate，不依赖 Mapper / Web Result / UserHolder / Controller / 查询服务。
 *
 * <p>状态转换由 {@code order-submission-transition.lua} 原子执行：
 * 身份冲突 fail-closed、PERSISTED 不回退、记录缺失可由消息身份创建；
 * 所有意外返回码、Lua null、Redis 异常均上抛，不静默成功。</p>
 */
@Component
public class RedisOrderSubmissionStatusRepository {

    private static final DefaultRedisScript<Long> TRANSITION_SCRIPT;

    static {
        TRANSITION_SCRIPT = new DefaultRedisScript<>();
        TRANSITION_SCRIPT.setLocation(new ClassPathResource("order-submission-transition.lua"));
        TRANSITION_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public void markProcessing(VoucherOrder order) {
        transition(order, "PROCESSING", "订单处理中");
    }

    public void markPersisted(VoucherOrder order) {
        transition(order, "PERSISTED", "订单已确认落库");
    }

    public void markFailed(VoucherOrder order, String safeMessage) {
        transition(order, "FAILED", safeMessage);
    }

    public Optional<OrderSubmissionRecord> find(long orderId) {
        String key = TransactionRedisConstants.ORDER_SUBMISSION_KEY_PREFIX + orderId;
        Map<Object, Object> fields = stringRedisTemplate.opsForHash().entries(key);
        if (fields == null || fields.isEmpty()) {
            return Optional.empty();
        }
        String stateRaw = toStringOrNull(fields.get("state"));
        String userIdRaw = toStringOrNull(fields.get("userId"));
        String voucherIdRaw = toStringOrNull(fields.get("voucherId"));
        String message = toStringOrNull(fields.get("message"));
        String updatedAtRaw = toStringOrNull(fields.get("updatedAt"));
        if (stateRaw == null || userIdRaw == null || voucherIdRaw == null || message == null || updatedAtRaw == null) {
            throw new IllegalStateException("提交状态记录字段不完整，fail-closed：orderId=" + orderId);
        }
        long userId = parseLongField(userIdRaw, "userId", orderId);
        long voucherId = parseLongField(voucherIdRaw, "voucherId", orderId);
        long updatedAt = parseLongField(updatedAtRaw, "updatedAt", orderId);
        OrderSubmissionState state;
        try {
            state = OrderSubmissionState.parse(stateRaw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("提交状态记录状态非法，fail-closed：orderId=" + orderId, e);
        }
        return Optional.of(new OrderSubmissionRecord(orderId, state, userId, voucherId, message, updatedAt));
    }

    private void transition(VoucherOrder order, String targetState, String message) {
        Long result = stringRedisTemplate.execute(
                TRANSITION_SCRIPT,
                Collections.emptyList(),
                String.valueOf(order.getId()),
                String.valueOf(order.getUserId()),
                String.valueOf(order.getVoucherId()),
                targetState,
                message,
                String.valueOf(System.currentTimeMillis()),
                String.valueOf(TransactionRedisConstants.ORDER_SUBMISSION_TTL));
        if (result == null) {
            throw new IllegalStateException("提交状态转换返回 null，无法确认结果，fail-closed：orderId=" + order.getId());
        }
        long code = result.longValue();
        if (code == 2L) {
            throw new IllegalStateException("提交状态身份冲突：记录不属于当前用户/优惠券，fail-closed：orderId=" + order.getId());
        }
        if (code == 3L) {
            throw new IllegalStateException("提交状态已有记录字段损坏，fail-closed：orderId=" + order.getId());
        }
        if (code != 0L) {
            throw new IllegalStateException("提交状态转换失败（返回码 " + code + "），fail-closed：orderId=" + order.getId());
        }
    }

    private long parseLongField(String raw, String field, long orderId) {
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("提交状态记录字段 " + field + " 非法，fail-closed：orderId=" + orderId, e);
        }
    }

    private String toStringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}
