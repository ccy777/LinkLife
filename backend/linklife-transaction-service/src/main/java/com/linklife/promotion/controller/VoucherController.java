package com.linklife.promotion.controller;


import com.linklife.common.core.api.Result;
import com.linklife.promotion.entity.Voucher;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.promotion.service.IVoucherService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 */
@RestController
@RequestMapping("/voucher")
public class VoucherController {

    @Resource
    private IVoucherService voucherService;

    /**
     * 新增秒杀券
     * @param voucher 优惠券信息，包含秒杀信息
     * @return 优惠券id
     */
    @PostMapping("seckill")
    public Result addSeckillVoucher(@RequestBody Voucher voucher) {
        if (voucher == null) {
            throw new BusinessException("优惠券信息不合法");
        }
        voucherService.addSeckillVoucher(voucher);
        if (voucher.getId() == null) {
            throw new BusinessException("秒杀券保存失败");
        }
        return Result.ok(voucher.getId());
    }

    /**
     * 新增普通券
     * @param voucher 优惠券信息
     * @return 优惠券id
     */
    @PostMapping
    public Result addVoucher(@RequestBody Voucher voucher) {
        if (voucher == null) {
            throw new BusinessException("优惠券信息不合法");
        }
        boolean saved = voucherService.save(voucher);
        if (!saved) {
            throw new BusinessException("优惠券保存失败");
        }
        // 成功响应必须真的携带数据库生成的 ID；未生成 ID 时不得返回 success
        if (voucher.getId() == null) {
            throw new BusinessException("优惠券保存失败");
        }
        return Result.ok(voucher.getId());
    }


    /**
     * 查询店铺的优惠券列表
     * @param shopId 店铺id
     * @return 优惠券列表
     */
    @GetMapping("/list/{shopId}")
    public Result queryVoucherOfShop(@PathVariable("shopId") Long shopId) {
       return voucherService.queryVoucherOfShop(shopId);
    }
}
