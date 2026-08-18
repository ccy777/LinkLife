package com.linklife.trade.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 当前用户订单分页 DTO：不直接暴露 MyBatis-Plus Page 或数据库 Entity。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class VoucherOrderPageDTO {
    private Integer current;
    private Integer size;
    private Long total;
    private List<VoucherOrderDTO> records;
}
