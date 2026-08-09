package com.linklife.trade.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.exception.BusinessException;
import com.linklife.trade.dto.VoucherOrderDTO;
import com.linklife.trade.dto.VoucherOrderPageDTO;
import com.linklife.trade.entity.VoucherOrder;
import com.linklife.trade.mapper.VoucherOrderMapper;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * 用户订单只读查询服务：单笔按 id+userId 查询、分页按当前用户隔离，
 * 排序固定 create_time DESC、id DESC；返回 DTO，不暴露 Entity/Page。
 */
@Component
public class OrderQueryService {

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    public VoucherOrderDTO getOrder(long orderId) {
        Long userId = requireCurrentUserId();
        VoucherOrder order = voucherOrderMapper.selectOne(
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getId, orderId)
                        .eq(VoucherOrder::getUserId, userId));
        return order == null ? null : VoucherOrderDTO.from(order);
    }

    public VoucherOrderPageDTO pageMine(int current, int size) {
        Long userId = requireCurrentUserId();
        Page<VoucherOrder> page = voucherOrderMapper.selectPage(
                new Page<>(current, size),
                new LambdaQueryWrapper<VoucherOrder>()
                        .eq(VoucherOrder::getUserId, userId)
                        .orderByDesc(VoucherOrder::getCreateTime)
                        .orderByDesc(VoucherOrder::getId));
        List<VoucherOrderDTO> records = page.getRecords().stream()
                .map(VoucherOrderDTO::from)
                .toList();
        return new VoucherOrderPageDTO(current, size, page.getTotal(), records);
    }

    private Long requireCurrentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BusinessException("请先登录");
        }
        return userId;
    }
}
