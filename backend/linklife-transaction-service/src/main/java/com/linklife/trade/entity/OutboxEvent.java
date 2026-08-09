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
 * 本地 Outbox 事件（tb_outbox_event）。
 *
 * <p>event_id 为应用内 UUID（不依赖 Redis）；business_key 为确定性业务唯一键
 * VOUCHER_ORDER:CLOSED:{orderId}:V1；双唯一约束防止同一业务事件重复落条。</p>
 *
 * <p>租约字段 lock_token/locked_until/processing_started_time/completed_time 允许为空；
 * 新建 PENDING 事件不持有活动租约，只有 PROCESSING 状态要求租约字段有值（由后续 017E 服务逻辑保证）。</p>
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("tb_outbox_event")
public class OutboxEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 全局唯一事件 id（应用内 UUID） */
    private String eventId;

    /** 确定性业务唯一键：VOUCHER_ORDER:CLOSED:{orderId}:V1 */
    private String businessKey;

    /** 聚合类型：VOUCHER_ORDER */
    private String aggregateType;

    /** 聚合 id：orderId */
    private Long aggregateId;

    /** 事件类型：ORDER_CLOSED */
    private String eventType;

    /** 事件版本：1 */
    private Integer eventVersion;

    /** JSON payload（最小必要字段） */
    private String payload;

    /** 状态：PENDING/PROCESSING/SUCCESS/DEAD */
    private String status;

    /** 重试次数 */
    private Integer retryCount;

    /** 下次可处理时间 */
    private LocalDateTime nextRetryTime;

    /** 领取令牌（可空，仅 PROCESSING 要求有值） */
    private String lockToken;

    /** 租约到期时间（可空） */
    private LocalDateTime lockedUntil;

    /** 领取开始时间（可空） */
    private LocalDateTime processingStartedTime;

    /** 最近错误码（可空） */
    private String lastErrorCode;

    /** 创建时间 */
    private LocalDateTime createdTime;

    /** 更新时间 */
    private LocalDateTime updatedTime;

    /** 完成时间（可空） */
    private LocalDateTime completedTime;
}
