package com.linklife.common.web.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.common.core.context.UserContext;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UserContextFilterTest {

    private final UserContextFilter filter = new UserContextFilter(new ObjectMapper());

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void headerAbsentLeavesAnonymousAndClears() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void positiveUserIdSetsContextAndClearsAfterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-LinkLife-User-Id", "7");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> {
            assertThat(UserContext.getUserId()).isEqualTo(7L);
        };

        filter.doFilter(request, response, chain);

        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void invalidUserIdReturns401AndDoesNotChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-LinkLife-User-Id", "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(chain.getRequest()).isNull();
        assertThat(response.getContentAsString()).contains("未登录");
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void nonPositiveUserIdIsFailClosed() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-LinkLife-User-Id", "0");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void exceptionPathStillClearsContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-LinkLife-User-Id", "9");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, resp) -> {
            throw new IllegalStateException("boom");
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(UserContext.getUserId()).isNull();
    }
}
