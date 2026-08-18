package com.linklife.trade.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 订单状态日志（tb_order_status_log）：业务审计记录，不是 Outbox 的替代品。
 *
 * <p>唯一约束：UNIQUE(idempotency_key) 与 UNIQUE(order_id, from_status, to_status)；
 * idempotency_key = ORDER_STATUS:{orderId}:{fromStatus}:{toStatus}。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_order_status_log")
public class OrderStatusLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 订单 id */
    private Long orderId;

    /** 迁移前状态（1 UNPAID） */
    private Integer fromStatus;

    /** 迁移后状态（4 CANCELED） */
    private Integer toStatus;

    /** 触发来源：USER_CANCEL | TIMEOUT_CLOSE（审计属性） */
    private String triggerType;

    /** 操作人类型：USER | SYSTEM */
    private String operatorType;

    /** 操作人 id（系统触发为空） */
    private Long operatorId;

    /** 稳定原因码 */
    private String reasonCode;

    /** 限长稳定文案，禁止 SQL/堆栈/Token/Redis 地址等敏感信息 */
    private String reasonDetail;

    /** 业务幂等键：ORDER_STATUS:{orderId}:{fromStatus}:{toStatus} */
    private String idempotencyKey;

    /** 创建时间（命令 now） */
    private LocalDateTime createdTime;
}
