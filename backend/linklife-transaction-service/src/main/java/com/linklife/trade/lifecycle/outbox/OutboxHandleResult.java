package com.linklife.trade.lifecycle.outbox;

/**
 * Outbox 事件处理结果（不可变）。
 *
 * <p>SUCCESS 不携带错误码；RETRYABLE_FAILURE/FATAL_FAILURE 必须携带稳定错误码。
 * 错误码只允许稳定字符 {@code [A-Z0-9_:-]+}、非空且长度不超过 64；
 * 禁止把异常堆栈、SQL、Redis 地址、payload 或用户数据放入错误码。</p>
 */
public record OutboxHandleResult(OutboxHandleResultType type, String errorCode) {

    private static final int MAX_ERROR_CODE_LENGTH = 64;
    private static final String ERROR_CODE_PATTERN = "[A-Z0-9_:-]+";

    public OutboxHandleResult {
        if (type == null) {
            throw new IllegalArgumentException("处理结果类型不能为空");
        }
        if (type == OutboxHandleResultType.SUCCESS) {
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

    public static OutboxHandleResult success() {
        return new OutboxHandleResult(OutboxHandleResultType.SUCCESS, null);
    }

    public static OutboxHandleResult retryable(String errorCode) {
        return new OutboxHandleResult(OutboxHandleResultType.RETRYABLE_FAILURE, errorCode);
    }

    public static OutboxHandleResult fatal(String errorCode) {
        return new OutboxHandleResult(OutboxHandleResultType.FATAL_FAILURE, errorCode);
    }

    /**
     * 处理结果类型。
     */
    public enum OutboxHandleResultType {
        SUCCESS,
        RETRYABLE_FAILURE,
        FATAL_FAILURE
    }
}
