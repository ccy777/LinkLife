package com.linklife.common.web.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.common.core.api.Result;
import com.linklife.common.core.context.UserContext;
import com.linklife.common.core.contract.InternalHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 可信内部 Header → ThreadLocal UserContext；请求结束 finally clear。
 * Header 缺失 → anonymous；存在但非正 Long → 401 fail-closed。
 */
@Component
public class UserContextFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    public UserContextFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(InternalHeaders.X_LINKLIFE_USER_ID);
        if (header == null || header.isBlank()) {
            try {
                filterChain.doFilter(request, response);
            } finally {
                UserContext.clear();
            }
            return;
        }
        long userId;
        try {
            userId = Long.parseLong(header.trim());
            if (userId <= 0L) {
                throw new NumberFormatException("userId 必须为正整数");
            }
        } catch (NumberFormatException e) {
            writeUnauthorized(response);
            return;
        }
        UserContext.set(userId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(Result.fail("未登录")));
    }
}
