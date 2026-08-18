package com.linklife.common.core.context;

/**
 * 认证主体：Stage 4 只传播真正需要的 userId（正整数）。
 */
public final class UserPrincipal {

    private final long userId;

    private UserPrincipal(long userId) {
        this.userId = userId;
    }

    public static UserPrincipal of(long userId) {
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId 必须为正整数");
        }
        return new UserPrincipal(userId);
    }

    public long getUserId() {
        return userId;
    }
}
