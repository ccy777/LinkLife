package com.linklife.trade.dto;

import com.linklife.trade.submission.OrderSubmissionState;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单提交状态查询 DTO：描述异步提交过程，不暴露 userId 与内部异常。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderSubmissionStatusDTO {
    /** 18-digit snowflake id; serialize as JSON string so JavaScript keeps exact digits. */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;
    private OrderSubmissionState state;
    private String message;
    private Long updatedAt;
}
