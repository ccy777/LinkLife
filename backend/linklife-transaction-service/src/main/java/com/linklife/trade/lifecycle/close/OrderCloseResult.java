package com.linklife.trade.lifecycle.close;

/**
 * 统一订单关闭事务结果。
 *
 * <p>{@code DATA_INCONSISTENT} 只用于表达“尚未发生持久化写入时已发现的数据异常”；
 * 一旦订单 CAS 已成功，后续库存/日志/Outbox 失败必须抛出 unchecked exception 使事务整体回滚，
 * 不得返回 {@code DATA_INCONSISTENT} 后正常提交。</p>
 */
public enum OrderCloseResult {
    /** 本次调用成功关闭（CAS affected=1，已完成库存+1/日志/Outbox） */
    CLOSED,
    /** 订单已是 CANCELED，重复关闭幂等 */
    ALREADY_CANCELED,
    /** 订单不存在或不属于当前用户（用户场景） */
    NOT_FOUND,
    /** 订单状态不可关闭（PAID/USED/REFUNDING/REFUNDED，或超时已不满足 cutoff） */
    NOT_CLOSABLE,
    /** 条件本应满足但 CAS=0，或仍为 UNPAID 却无法关闭（未发生写入前发现的数据异常） */
    DATA_INCONSISTENT
}
