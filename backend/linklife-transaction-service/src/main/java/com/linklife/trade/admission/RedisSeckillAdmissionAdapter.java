package com.linklife.trade.admission;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.Collections;

/**
 * Redis 秒杀准入适配器：独占执行 seckill.lua 并解释 0—6 返回码。
 *
 * <p>不生成订单 ID、不读取 {@code UserHolder}、不返回 Web {@code Result}、
 * 不访问 MySQL、不启动线程、不修改 Lua / Redis Key / 返回码。</p>
 */
@Component
public class RedisSeckillAdmissionAdapter {

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 执行秒杀准入 Lua，返回领域化判定。
     *
     * <p>Lua 参数顺序固定为：voucherId、userId、orderId、currentTimeMillis、submissionTtlSeconds；
     * KEYS 为空集合，保持 long 精度。</p>
     */
    public SeckillAdmissionDecision admit(
            long voucherId,
            long userId,
            long orderId,
            long currentTimeMillis,
            long submissionTtlSeconds) {
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                String.valueOf(voucherId),
                String.valueOf(userId),
                String.valueOf(orderId),
                String.valueOf(currentTimeMillis),
                String.valueOf(submissionTtlSeconds)
        );
        return SeckillAdmissionDecision.from(result);
    }
}
