package com.linklife.common.core.context;

/**
 * 请求级用户上下文（ThreadLocal）。请求结束必须 clear，禁止跨请求残留。
 * common-core 不依赖 Servlet/WebFlux/Redis/MyBatis。
 */
public final class UserContext {

    private static final ThreadLocal<UserPrincipal> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(long userId) {
        HOLDER.set(UserPrincipal.of(userId));
    }

    public static UserPrincipal get() {
        return HOLDER.get();
    }

    public static Long getUserId() {
        UserPrincipal principal = HOLDER.get();
        return principal == null ? null : principal.getUserId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
