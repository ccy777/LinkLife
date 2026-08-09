package com.linklife.identity.redis;

/**
 * Identity Redis namespace 契约（DB 0，全部 identity:*）。
 */
public class IdentityRedisConstants {

    public static final String LOGIN_CODE_KEY = "identity:login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_CODE_COOLDOWN_KEY = "identity:login:code:cooldown:";
    public static final String LOGIN_CODE_ATTEMPT_KEY = "identity:login:code:attempt:";
    public static final String LOGIN_CODE_LOCK_KEY = "identity:lock:login:code:";
    public static final Long LOGIN_CODE_COOLDOWN_SECONDS = 60L;
    public static final Long LOGIN_CODE_MAX_ATTEMPTS = 5L;
    public static final String LOGIN_USER_KEY = "identity:login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;
    public static final String USER_SIGN_KEY = "identity:sign:";

    private IdentityRedisConstants() {
    }
}
