package com.linklife.common.core.contract;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SessionKeyContractTest {

    @Test
    void sessionKeyContractFrozen() {
        assertThat(SessionKeyContract.NEW_SESSION_PREFIX).isEqualTo("identity:login:token:");
        assertThat(SessionKeyContract.LEGACY_SESSION_PREFIX).isEqualTo("login:token:");
        assertThat(SessionKeyContract.SESSION_TTL_MINUTES).isEqualTo(36000L);
        assertThat(SessionKeyContract.SESSION_USER_ID_FIELD).isEqualTo("id");
    }
}
