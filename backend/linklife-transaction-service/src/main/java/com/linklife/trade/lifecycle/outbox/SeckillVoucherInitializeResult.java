package com.linklife.trade.lifecycle.outbox;

/**
 * 秒杀券 Redis 原子初始化结果（不可变）。
 *
 * <p>SUCCESS 不携带错误码；RETRYABLE_FAILURE/FATAL_FAILURE 必须携带稳定错误码
 * （非空、≤64、仅 {@code [A-Z0-9_:-]+}），不包含异常堆栈、payload 或秘密。</p>
 */
public record SeckillVoucherInitializeResult(InitializeOutcome outcome, String errorCode) {

    private static final int MAX_ERROR_CODE_LENGTH = 64;
    private static final String ERROR_CODE_PATTERN = "[A-Z0-9_:-]+";

    public SeckillVoucherInitializeResult {
        if (outcome == null) {
            throw new IllegalArgumentException("初始化结果类型不能为空");
        }
        if (outcome == InitializeOutcome.SUCCESS) {
            if (errorCode != null) {
                throw new IllegalArgumentException("SUCCESS 不允许携带错误码");
            }
        } else {
            if (errorCode == null || errorCode.isBlank()) {
                throw new IllegalArgumentException("失败结果必须携带非空错误码");
            }
            if (errorCode.length() > MAX_ERROR_CODE_LENGTH) {
                throw new IllegalArgumentException("错误码长度不能超过 " + MAX_ERROR_CODE_LENGTH);
            }
            if (!errorCode.matches(ERROR_CODE_PATTERN)) {
                throw new IllegalArgumentException("错误码只允许稳定字符 [A-Z0-9_:-]+");
            }
        }
    }

    public static SeckillVoucherInitializeResult success() {
        return new SeckillVoucherInitializeResult(InitializeOutcome.SUCCESS, null);
    }

    public static SeckillVoucherInitializeResult retryable(String errorCode) {
        return new SeckillVoucherInitializeResult(InitializeOutcome.RETRYABLE_FAILURE, errorCode);
    }

    public static SeckillVoucherInitializeResult fatal(String errorCode) {
        return new SeckillVoucherInitializeResult(InitializeOutcome.FATAL_FAILURE, errorCode);
    }

    public enum InitializeOutcome {
        SUCCESS,
        RETRYABLE_FAILURE,
        FATAL_FAILURE
    }
}
