package com.linklife.gateway.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.common.core.api.Result;
import com.linklife.common.core.contract.InternalHeaders;
import com.linklife.common.core.contract.SessionKeyContract;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Gateway 正式 Session 认证（018C）：
 *
 * <ul>
 *   <li>在任何认证判断前删除客户端全部 X-LinkLife-* Header（大小写不敏感）；</li>
 *   <li>authorization Header → 会话双读：identity:login:token:{token} 优先，login:token:{token} legacy 兼容；</li>
 *   <li>读 id（正 Long）并刷新 TTL = 36000 分钟（Stage 3 语义）；</li>
 *   <li>只注入 X-LinkLife-User-Id；不注入 Admin/Nick/Icon；</li>
 *   <li>受保护路径无/无效会话 → 401（Result JSON）；公开路径无/无效会话 → 匿名放行。</li>
 * </ul>
 */
@Component
public class GatewaySessionAuthFilter implements GlobalFilter, Ordered {

    private final ReactiveStringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public GatewaySessionAuthFilter(ReactiveStringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest cleaned = stripInternalHeaders(exchange.getRequest());
        String token = cleaned.getHeaders().getFirst("authorization");
        boolean protectedRoute = RouteAccessPolicy.isProtected(cleaned.getPath().value(), cleaned.getMethod());

        return resolveUser(token).flatMap(userIdOpt -> {
            if (userIdOpt.isPresent()) {
                ServerHttpRequest withUser = cleaned.mutate()
                        .header(InternalHeaders.X_LINKLIFE_USER_ID, String.valueOf(userIdOpt.get()))
                        .build();
                return chain.filter(exchange.mutate().request(withUser).build());
            }
            if (protectedRoute) {
                return writeUnauthorized(exchange.mutate().request(cleaned).build());
            }
            return chain.filter(exchange.mutate().request(cleaned).build());
        });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    private ServerHttpRequest stripInternalHeaders(ServerHttpRequest request) {
        return request.mutate().headers(headers -> {
            java.util.List<String> internal = headers.keySet().stream()
                    .filter(k -> k.toLowerCase(Locale.ROOT).startsWith("x-linklife-"))
                    .toList();
            internal.forEach(headers::remove);
        }).build();
    }

    private Mono<Optional<Long>> resolveUser(String token) {
        if (token == null || token.isBlank()) {
            return Mono.just(Optional.empty());
        }
        String newKey = SessionKeyContract.NEW_SESSION_PREFIX + token;
        String legacyKey = SessionKeyContract.LEGACY_SESSION_PREFIX + token;
        // 018D 冻结语义：new key 存在 → 拥有优先权 → 内容损坏必须 fail-closed，不得回退 legacy；
        // 只有 new key 不存在时才查询 legacy。
        return redisTemplate.hasKey(newKey).flatMap(newExists -> {
            if (Boolean.TRUE.equals(newExists)) {
                return readAndRefresh(newKey);
            }
            return readAndRefresh(legacyKey);
        });
    }

    private Mono<Optional<Long>> readAndRefresh(String key) {
        return redisTemplate.opsForHash()
                .get(key, SessionKeyContract.SESSION_USER_ID_FIELD)
                .cast(Object.class)
                .flatMap(value -> {
                    Long userId = parsePositiveLong(value);
                    if (userId == null) {
                        // 非法/非正 id fail-closed：视为无有效会话（不回退 legacy）
                        return Mono.just(Optional.<Long>empty());
                    }
                    return redisTemplate.expire(key, Duration.ofMinutes(SessionKeyContract.SESSION_TTL_MINUTES))
                            .thenReturn(Optional.of(userId));
                })
                .defaultIfEmpty(Optional.empty());
    }

    private Long parsePositiveLong(Object value) {
        if (value == null) {
            return null;
        }
        try {
            long id = Long.parseLong(String.valueOf(value).trim());
            return id > 0L ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.fail("未登录"));
        } catch (JsonProcessingException e) {
            bytes = "{\"success\":false,\"errorMsg\":\"未登录\",\"data\":null,\"total\":null}"
                    .getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
