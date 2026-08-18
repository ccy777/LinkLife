package com.linklife.common.web.security;

import com.linklife.common.core.context.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Locale;
import java.util.Set;

/**
 * 通用管理写接口授权拦截器：只拦截 POST/PUT/PATCH/DELETE（注册方限定路径）。
 * 只依赖 UserContext + AdminAuthorizationProperties，不信任客户端角色 Header；
 * fail-closed：无用户上下文 401，非管理员/空配置 403。
 */
public class AdminMutationInterceptor implements HandlerInterceptor {

    private static final Set<String> PROTECTED_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final AdminAuthorizationProperties properties;

    public AdminMutationInterceptor(AdminAuthorizationProperties properties) {
        this.properties = properties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if ("GET".equalsIgnoreCase(method)
                || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method)) {
            return true;
        }
        if (!PROTECTED_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return true;
        }
        Long userId = UserContext.getUserId();
        if (userId == null) {
            response.setStatus(401);
            return false;
        }
        if (properties == null || !properties.isAdmin(userId)) {
            response.setStatus(403);
            return false;
        }
        return true;
    }
}
