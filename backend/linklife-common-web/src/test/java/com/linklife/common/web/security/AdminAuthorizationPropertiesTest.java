package com.linklife.common.web.security;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthorizationPropertiesTest {

    @Test
    void emptySetMeansNoAdmin() {
        AdminAuthorizationProperties properties = new AdminAuthorizationProperties();
        properties.setAdminUserIds(Set.of());
        assertThat(properties.isAdmin(1L)).isFalse();
        assertThat(properties.isAdmin(null)).isFalse();
    }

    @Test
    void configuredAdminPassesOthersFail() {
        AdminAuthorizationProperties properties = new AdminAuthorizationProperties();
        properties.setAdminUserIds(Set.of(1L, 2L));
        assertThat(properties.isAdmin(1L)).isTrue();
        assertThat(properties.isAdmin(2L)).isTrue();
        assertThat(properties.isAdmin(3L)).isFalse();
    }

    @Test
    void nonPositiveIdsFailStartupValidation() {
        AdminAuthorizationProperties properties = new AdminAuthorizationProperties();
        properties.setAdminUserIds(Set.of(0L));
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void internalSetIsDefensivelyCopied() {
        AdminAuthorizationProperties properties = new AdminAuthorizationProperties();
        Set<Long> mutable = new java.util.HashSet<>(Set.of(1L));
        properties.setAdminUserIds(mutable);
        mutable.add(99L);
        assertThat(properties.isAdmin(99L)).isFalse();
    }
}
