package com.linklife.promotion.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.common.core.api.Result;
import com.linklife.shared.event.SeckillVoucherCreatedEventPayload;
import com.linklife.shared.outbox.OutboxPublishCommand;
import com.linklife.shared.outbox.OutboxPublisher;
import com.linklife.promotion.entity.SeckillVoucher;
import com.linklife.promotion.entity.Voucher;
import com.linklife.promotion.mapper.VoucherMapper;
import com.linklife.promotion.service.ISeckillVoucherService;
import com.linklife.promotion.service.IVoucherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private OutboxPublisher outboxPublisher;
    @Resource
    private ObjectMapper objectMapper;

    private static final String OUTBOX_AGGREGATE_TYPE = "SECKILL_VOUCHER";
    private static final String OUTBOX_EVENT_TYPE = "SECKILL_VOUCHER_CREATED";
    private static final int OUTBOX_EVENT_VERSION = 1;
    private static final String OUTBOX_BUSINESS_KEY_PREFIX = "SECKILL_VOUCHER:CREATED:";
    private static final String OUTBOX_BUSINESS_KEY_SUFFIX = ":V1";

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        // 查询优惠券信息
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 返回结果
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        // 1.校验秒杀参数：库存、开始时间、结束时间均不得为空
        Integer stock = voucher.getStock();
        LocalDateTime beginTime = voucher.getBeginTime();
        LocalDateTime endTime = voucher.getEndTime();
        if (stock == null || beginTime == null || endTime == null) {
            throw new IllegalArgumentException("秒杀库存、开始时间和结束时间不能为空");
        }
        if (stock < 0) {
            throw new IllegalArgumentException("秒杀库存不能小于0");
        }
        if (!beginTime.isBefore(endTime)) {
            throw new IllegalArgumentException("秒杀开始时间必须早于结束时间");
        }
        // 保存优惠券
        boolean voucherSaved = save(voucher);
        if (!voucherSaved) {
            throw new IllegalStateException("优惠券保存失败，事务回滚");
        }
        // 基础券保存成功后必须已生成 ID；未生成时抛异常触发事务回滚，禁止写入 seckill:*:null
        if (voucher.getId() == null) {
            throw new IllegalStateException("优惠券保存失败：未生成 ID，事务回滚");
        }
        // 保存秒杀信息
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(stock);
        seckillVoucher.setBeginTime(beginTime);
        seckillVoucher.setEndTime(endTime);
        boolean seckillSaved = seckillVoucherService.save(seckillVoucher);
        if (!seckillSaved) {
            throw new IllegalStateException("秒杀券保存失败，事务回滚");
        }
        // 可靠初始化边界：同一 MySQL 本地事务内插入唯一 SECKILL_VOUCHER_CREATED Outbox，
        // Redis 初始化由 Outbox 驱动；本方法不再直接操作 Redis。
        String eventId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        SeckillVoucherCreatedEventPayload payload = new SeckillVoucherCreatedEventPayload(
                eventId,
                OUTBOX_EVENT_VERSION,
                voucher.getId(),
                stock,
                beginTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                endTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                now);
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("秒杀券创建 Outbox payload 序列化失败，事务回滚", e);
        }
        outboxPublisher.publish(new OutboxPublishCommand(
                OUTBOX_AGGREGATE_TYPE,
                voucher.getId(),
                OUTBOX_EVENT_TYPE,
                OUTBOX_EVENT_VERSION,
                OUTBOX_BUSINESS_KEY_PREFIX + voucher.getId() + OUTBOX_BUSINESS_KEY_SUFFIX,
                payloadJson,
                eventId,
                now));
    }
}
