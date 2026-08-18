package com.linklife.trade.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.trade.dto.OrderSubmissionStatusDTO;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.mapper.VoucherOrderMapper;
import com.linklife.trade.submission.OrderSubmissionRecord;
import com.linklife.trade.submission.OrderSubmissionState;
import com.linklife.trade.submission.RedisOrderSubmissionStatusRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 订单提交状态只读服务：MySQL 优先（最终事实来源）、Redis 表达异步过程、当前用户隔离。
 * Redis/MySQL 连接或执行异常 fail-closed 返回服务暂不可用，不伪造成 UNKNOWN。
 */
@Component
public class OrderSubmissionStatusService {

    private static final String PERSISTED_MESSAGE = "订单已确认落库";
    private static final String UNKNOWN_MESSAGE = "无法确认订单提交状态";

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private RedisOrderSubmissionStatusRepository submissionStatusRepository;

    public OrderSubmissionStatusDTO getSubmissionStatus(long orderId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        VoucherOrder order;
        try {
            order = voucherOrderMapper.selectOne(
                    new LambdaQueryWrapper<VoucherOrder>()
                            .eq(VoucherOrder::getId, orderId)
                            .eq(VoucherOrder::getUserId, userId));
        } catch (DataAccessException e) {
            throw new BusinessException("订单状态服务暂不可用", e);
        }
        if (order != null) {
            return new OrderSubmissionStatusDTO(orderId, OrderSubmissionState.PERSISTED,
                    PERSISTED_MESSAGE, toMillis(order));
        }

        OrderSubmissionRecord record;
        try {
            record = submissionStatusRepository.find(orderId).orElse(null);
        } catch (DataAccessException e) {
            throw new BusinessException("订单状态服务暂不可用", e);
        } catch (IllegalStateException e) {
            // 仓储记录损坏（字段缺失/非法数字/未知状态）：统一 fail-closed 为固定安全文案，
            // 不泄露内部字段、状态值或 Redis Key；保留 cause 便于内部诊断
            throw new BusinessException("订单状态服务暂不可用", e);
        }
        if (record == null || record.userId() != userId.longValue()) {
            return new OrderSubmissionStatusDTO(orderId, OrderSubmissionState.UNKNOWN, UNKNOWN_MESSAGE, null);
        }
        return new OrderSubmissionStatusDTO(orderId, record.state(), record.message(), record.updatedAt());
    }

    private long toMillis(VoucherOrder order) {
        LocalDateTime time = order.getUpdateTime() != null ? order.getUpdateTime() : order.getCreateTime();
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
