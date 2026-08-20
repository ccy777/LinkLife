package com.linklife.trade.dto;

import com.linklife.trade.entity.VoucherOrder;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户订单只读 DTO：不暴露 userId，不返回数据库 Entity。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderDTO {
    /** 18-digit snowflake id; serialize as JSON string so JavaScript keeps exact digits. */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;
    private Long voucherId;
    private Integer payType;
    private Integer status;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime useTime;
    private LocalDateTime refundTime;
    private LocalDateTime updateTime;

    public static VoucherOrderDTO from(VoucherOrder order) {
        VoucherOrderDTO dto = new VoucherOrderDTO();
        dto.setId(order.getId());
        dto.setVoucherId(order.getVoucherId());
        dto.setPayType(order.getPayType());
        dto.setStatus(order.getStatus());
        dto.setCreateTime(order.getCreateTime());
        dto.setPayTime(order.getPayTime());
        dto.setUseTime(order.getUseTime());
        dto.setRefundTime(order.getRefundTime());
        dto.setUpdateTime(order.getUpdateTime());
        return dto;
    }
}
