package com.linklife.common.core.exception;

/**
 * 项目业务异常：message 为面向用户的稳定提示，不携带堆栈、Token 或绝对路径。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }

    public BusinessException(String message, Throwable cause) {
        super(message, cause);
    }
}
