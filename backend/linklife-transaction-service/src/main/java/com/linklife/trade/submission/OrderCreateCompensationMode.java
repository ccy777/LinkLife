package com.linklife.trade.submission;

/**
 * 订单创建失败 Redis 补偿模式（冻结白名单）。
 *
 * <p>RESTORE_STOCK_AND_RELEASE_QUALIFICATION：MySQL 完全没有该 user/voucher 订单，
 * 恢复库存并释放一人一券资格（SREM）；RESTORE_STOCK_KEEP_QUALIFICATION：同 user/voucher
 * 已存在不同 orderId 的有效订单，只恢复库存、保留资格（不 SREM）。</p>
 */
public enum OrderCreateCompensationMode {
    RESTORE_STOCK_AND_RELEASE_QUALIFICATION,
    RESTORE_STOCK_KEEP_QUALIFICATION
}
