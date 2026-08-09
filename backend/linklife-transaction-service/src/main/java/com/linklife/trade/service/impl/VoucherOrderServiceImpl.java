package com.linklife.trade.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.api.Result;
import com.linklife.trade.redis.TransactionRedisConstants;
import com.linklife.shared.redis.RedisIdWorker;
import com.linklife.trade.admission.RedisSeckillAdmissionAdapter;
import com.linklife.trade.admission.SeckillAdmissionDecision;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.mapper.VoucherOrderMapper;
import com.linklife.trade.service.IVoucherOrderService;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;

/**
 * <p>
 * 秒杀订单接入服务：取得当前用户、生成订单 ID、调用 Redis 准入适配器并映射 API 结果。
 * </p>
 *
 * <p>注意：{@code Result.ok(orderId)} 仅表示 Redis 原子准入成功且订单消息已进入 Stream，
 * 属于<strong>异步提交已受理</strong>；不表示 MySQL 订单已经落库。MySQL 最终落库由
 * {@code OrderStreamConsumer} 消费后经事务服务完成。提交状态查询由 Stage 3C 提供。</p>
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedisSeckillAdmissionAdapter seckillAdmissionAdapter;

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return Result.fail("请先登录");
        }
        long orderId = redisIdWorker.nextId("order");
        // 执行 Lua 准入并显式传入当前时间戳（System.currentTimeMillis()）
        SeckillAdmissionDecision decision = seckillAdmissionAdapter.admit(
                voucherId, userId, orderId, System.currentTimeMillis(), TransactionRedisConstants.ORDER_SUBMISSION_TTL);
        return mapAdmissionResult(decision, orderId);
    }

    /**
     * 固定业务消息映射；ACCEPTED 返回订单 ID，其余为固定失败文案。
     */
    private Result mapAdmissionResult(SeckillAdmissionDecision decision, long orderId) {
        switch (decision) {
            case ACCEPTED:
                // 异步提交已受理：仅表示准入并入队，不表示 MySQL 已落库
                return Result.ok(orderId);
            case OUT_OF_STOCK:
                return Result.fail("库存不足");
            case DUPLICATE_ORDER:
                return Result.fail("不能重复下单");
            case NOT_INITIALIZED:
                return Result.fail("秒杀活动未初始化");
            case NOT_STARTED:
                return Result.fail("秒杀尚未开始");
            case ENDED:
                return Result.fail("秒杀已经结束");
            case UNAVAILABLE:
            default:
                return Result.fail("秒杀服务暂时不可用");
        }
    }
}
