package com.linklife.trade.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.linklife.common.core.api.Result;
import com.linklife.trade.entity.VoucherOrder;

/**
 * 秒杀订单服务。
 *
 * <p>{@link #seckillVoucher(Long)} 成功仅表示 Redis Lua 原子准入通过且订单消息已进入 Stream，
 * 属于异步提交已受理；MySQL 订单最终落库由 Stream 消费者与事务服务完成，不包含在 API 成功语义内。</p>
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);
}
