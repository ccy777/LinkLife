package com.linklife.identity.redis;

import com.linklife.common.core.contract.SessionKeyContract;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Identity Redis namespace 契约：全部 identity:*；新 login 只写 identity session；
 * legacy 前缀仅作为 Gateway 兼容读与 logout 清理目标。
 */
class IdentityRedisNamespaceContractTest {

    @Test
    void allIdentityConstantsUseIdentityPrefix() {
        assertThat(IdentityRedisConstants.LOGIN_CODE_KEY).isEqualTo("identity:login:code:");
        assertThat(IdentityRedisConstants.LOGIN_CODE_COOLDOWN_KEY).isEqualTo("identity:login:code:cooldown:");
        assertThat(IdentityRedisConstants.LOGIN_CODE_ATTEMPT_KEY).isEqualTo("identity:login:code:attempt:");
        assertThat(IdentityRedisConstants.LOGIN_CODE_LOCK_KEY).isEqualTo("identity:lock:login:code:");
        assertThat(IdentityRedisConstants.LOGIN_USER_KEY).isEqualTo("identity:login:token:");
        assertThat(IdentityRedisConstants.USER_SIGN_KEY).isEqualTo("identity:sign:");
    }

    @Test
    void newSessionPrefixMatchesContract() {
        assertThat(IdentityRedisConstants.LOGIN_USER_KEY).isEqualTo(SessionKeyContract.NEW_SESSION_PREFIX);
        assertThat(SessionKeyContract.LEGACY_SESSION_PREFIX).isEqualTo("login:token:");
        assertThat(SessionKeyContract.SESSION_USER_ID_FIELD).isEqualTo("id");
    }

    @Test
    void ttlMatchesStage3() {
        assertThat(IdentityRedisConstants.LOGIN_USER_TTL).isEqualTo(SessionKeyContract.SESSION_TTL_MINUTES);
        assertThat(IdentityRedisConstants.LOGIN_USER_TTL).isEqualTo(36000L);
    }
}
