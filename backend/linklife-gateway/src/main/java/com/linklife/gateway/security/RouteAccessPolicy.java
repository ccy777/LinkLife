package com.linklife.gateway.security;

import org.springframework.http.HttpMethod;

/**
 * Gateway public/protected 路由策略（按 Stage 3 实际拦截器语义复算，不随意扩大公开接口）。
 *
 * <p>公开：POST /api/user/code、POST /api/user/login；
 * GET/HEAD/OPTIONS /api/shop/**、/api/shop-type/**、/api/voucher/**、/api/files/**；
 * GET /api/blog/hot。其余正常业务路径默认需要有效 Session。</p>
 */
public final class RouteAccessPolicy {

    private RouteAccessPolicy() {
    }

    public static boolean isProtected(String path, HttpMethod method) {
        if (method == null || HttpMethod.OPTIONS.equals(method)) {
            return false;
        }
        String normalized = stripApiPrefix(path);
        boolean read = HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method);
        if (read) {
            if (startsWith(normalized, "/shop")
                    || startsWith(normalized, "/shop-type")
                    || startsWith(normalized, "/voucher")
                    || startsWith(normalized, "/files")
                    || "/blog/hot".equals(normalized)) {
                return false;
            }
        }
        if (HttpMethod.POST.equals(method)) {
            if ("/user/code".equals(normalized) || "/user/login".equals(normalized)) {
                return false;
            }
        }
        return true;
    }

    private static String stripApiPrefix(String path) {
        if (path == null) {
            return "";
        }
        if (!path.startsWith("/api")) {
            return path;
        }
        return path.length() == 4 ? "/" : path.substring(4);
    }

    private static boolean startsWith(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + "/");
    }
}
