package com.linklife.common.core.contract;

/**
 * Gateway ↔ Identity 会话 Key 共享契约（authentication boundary shared contract）。
 *
 * <p>SESSION_TTL_MINUTES=36000 与 Stage 3 RedisConstants.LOGIN_USER_TTL 数值一致，
 * Gateway 刷新与 Identity 写入均使用同一 TTL 语义（36000 分钟），不修改 Stage 3 Token TTL。</p>
 */
public final class SessionKeyContract {

    public static final String NEW_SESSION_PREFIX = "identity:login:token:";
    public static final String LEGACY_SESSION_PREFIX = "login:token:";
    public static final long SESSION_TTL_MINUTES = 36000L;
    public static final String SESSION_USER_ID_FIELD = "id";

    private SessionKeyContract() {
    }
}
