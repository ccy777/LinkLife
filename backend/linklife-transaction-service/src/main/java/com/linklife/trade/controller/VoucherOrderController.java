package com.linklife.trade.controller;

import com.linklife.common.core.api.Result;
import com.linklife.trade.application.OrderLifecycleService;
import com.linklife.trade.application.OrderQueryService;
import com.linklife.trade.application.OrderSubmissionStatusService;
import com.linklife.trade.dto.VoucherOrderDTO;
import com.linklife.trade.service.IVoucherOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

/**
 * 秒杀订单入口。POST 路径与响应结构保持不变；Stage 3C 新增提交状态与用户订单只读查询。
 *
 * <p>成功响应中的 data 为订单 ID，但仅表示异步提交已受理（已进入 Stream 且提交状态 ACCEPTED），
 * 不表示 MySQL 订单已经落库；落库由异步消费者完成。</p>
 *
 * <p>提交状态（ACCEPTED/PROCESSING/PERSISTED/FAILED/UNKNOWN）描述异步提交过程，
 * 与订单业务状态（1—6）严格区分；Stage 3D 016A 实现订单业务状态模型与用户取消，
 * Stage 3E 完成库存补偿与本地 Outbox。</p>
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {

    private static final int MAX_PAGE_SIZE = 50;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private OrderSubmissionStatusService orderSubmissionStatusService;

    @Resource
    private OrderQueryService orderQueryService;

    @Resource
    private OrderLifecycleService orderLifecycleService;

    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }

    /**
     * 当前用户取消自己的未支付订单（UNPAID → CANCELED），成功或幂等成功时 data 为订单 ID。
     *
     * <p>不存在或他人订单统一返回“订单不存在”；PAID/USED/REFUNDING/REFUNDED 返回
     * “当前订单状态不可取消”。取消通过数据库原子条件更新保证并发正确。</p>
     *
     * <p>本阶段只完成订单状态关闭：MySQL 库存、Redis 库存与一人一券资格的可靠补偿将在
     * Stage 3E 通过本地事务与 Outbox 完成。在 Stage 3E 完成前，不得把取消能力描述为生产级完整交易闭环。</p>
     */
    @PostMapping("{orderId}/cancel")
    public Result cancelOrder(@PathVariable("orderId") Long orderId) {
        if (orderId == null || orderId <= 0) {
            return Result.fail("参数错误：orderId 必须大于 0");
        }
        return Result.ok(String.valueOf(orderLifecycleService.cancelByCurrentUser(orderId)));
    }

    @GetMapping("submissions/{orderId}")
    public Result submissionStatus(@PathVariable("orderId") Long orderId) {
        if (orderId == null || orderId <= 0) {
            return Result.fail("参数错误：orderId 必须大于 0");
        }
        return Result.ok(orderSubmissionStatusService.getSubmissionStatus(orderId));
    }

    @GetMapping("{orderId}")
    public Result getOrder(@PathVariable("orderId") Long orderId) {
        if (orderId == null || orderId <= 0) {
            return Result.fail("参数错误：orderId 必须大于 0");
        }
        VoucherOrderDTO dto = orderQueryService.getOrder(orderId);
        if (dto == null) {
            return Result.fail("订单不存在");
        }
        return Result.ok(dto);
    }

    @GetMapping("mine")
    public Result mine(@RequestParam(value = "current", defaultValue = "1") Integer current,
                       @RequestParam(value = "size", defaultValue = "10") Integer size) {
        if (current == null || current < 1) {
            return Result.fail("参数错误：current 必须大于等于 1");
        }
        if (size == null || size < 1 || size > MAX_PAGE_SIZE) {
            return Result.fail("参数错误：size 必须在 1 到 50 之间");
        }
        return Result.ok(orderQueryService.pageMine(current, size));
    }
}
