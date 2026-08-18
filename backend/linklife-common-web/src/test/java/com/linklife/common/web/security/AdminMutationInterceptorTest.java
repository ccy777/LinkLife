package com.linklife.common.web.security;

import com.linklife.common.core.context.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AdminMutationInterceptorTest {

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private AdminMutationInterceptor interceptor(Set<Long> admins) {
        AdminAuthorizationProperties properties = new AdminAuthorizationProperties();
        properties.setAdminUserIds(admins);
        return new AdminMutationInterceptor(properties);
    }

    @Test
    void getPassesRegardless() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/shop");
        assertThat(interceptor(Set.of()).preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }

    @Test
    void noUserContextReturns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shop");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor(Set.of(1L)).preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    @Test
    void nonAdminReturns403() throws Exception {
        UserContext.set(2L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shop");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor(Set.of(1L)).preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void emptyAdminSetIs403() throws Exception {
        UserContext.set(1L);
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/shop");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(interceptor(Set.of()).preHandle(request, response, new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(403);
    }

    @Test
    void adminPasses() throws Exception {
        UserContext.set(1L);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/shop");
        assertThat(interceptor(Set.of(1L)).preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
    }
}
