package com.linklife.trade.lifecycle.close;

import java.time.LocalDateTime;

/**
 * 统一订单关闭命令（不可变）。
 *
 * <p>校验规则：orderId 必须大于 0；triggerType/reasonCode/now 非空；
 * USER_CANCEL 必须携带 userId 且 cutoff 为空；TIMEOUT_CLOSE 必须携带 cutoff 且 userId 为空；
 * triggerType 与 reasonCode 必须匹配。</p>
 *
 * <p>本命令不从 UserHolder、Controller 或调度器内部读取上下文，调用方负责提供全部输入。</p>
 *
 * @param orderId     订单 ID
 * @param userId      用户 ID（USER_CANCEL 必填；TIMEOUT_CLOSE 必须为空）
 * @param triggerType 触发来源
 * @param cutoff      超时截止时间（TIMEOUT_CLOSE 必填；USER_CANCEL 必须为空）
 * @param reasonCode  稳定原因码
 * @param now         关闭时间（整轮固定，可测试时间源）
 */
public record OrderCloseCommand(
        long orderId,
        Long userId,
        OrderCloseTriggerType triggerType,
        LocalDateTime cutoff,
        OrderCloseReasonCode reasonCode,
        LocalDateTime now) {

    public OrderCloseCommand {
        if (orderId <= 0) {
            throw new IllegalArgumentException("orderId 必须大于 0");
        }
        if (triggerType == null) {
            throw new IllegalArgumentException("triggerType 不能为空");
        }
        if (reasonCode == null) {
            throw new IllegalArgumentException("reasonCode 不能为空");
        }
        if (now == null) {
            throw new IllegalArgumentException("now 不能为空");
        }
        switch (triggerType) {
            case USER_CANCEL -> {
                if (userId == null) {
                    throw new IllegalArgumentException("USER_CANCEL 必须携带 userId");
                }
                if (cutoff != null) {
                    throw new IllegalArgumentException("USER_CANCEL 不允许携带 cutoff");
                }
                if (reasonCode != OrderCloseReasonCode.USER_CANCEL) {
                    throw new IllegalArgumentException("triggerType 与 reasonCode 不匹配");
                }
            }
            case TIMEOUT_CLOSE -> {
                if (userId != null) {
                    throw new IllegalArgumentException("TIMEOUT_CLOSE 不允许携带 userId");
                }
                if (cutoff == null) {
                    throw new IllegalArgumentException("TIMEOUT_CLOSE 必须携带 cutoff");
                }
                if (reasonCode != OrderCloseReasonCode.TIMEOUT_EXPIRED) {
                    throw new IllegalArgumentException("triggerType 与 reasonCode 不匹配");
                }
            }
        }
    }
}
