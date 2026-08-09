package com.linklife.common.core.context;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserContextContractTest {

    @Test
    void absentContextIsAnonymous() {
        UserContext.clear();
        assertThat(UserContext.get()).isNull();
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void setAndGetPositiveUserId() {
        UserContext.clear();
        UserContext.set(42L);
        assertThat(UserContext.getUserId()).isEqualTo(42L);
        assertThat(UserContext.get().getUserId()).isEqualTo(42L);
        UserContext.clear();
    }

    @Test
    void clearRemovesPrincipal() {
        UserContext.set(1L);
        UserContext.clear();
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void nonPositiveUserIdRejected() {
        assertThatThrownBy(() -> UserContext.set(0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserContext.set(-5L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserPrincipal.of(0L)).isInstanceOf(IllegalArgumentException.class);
    }
}
