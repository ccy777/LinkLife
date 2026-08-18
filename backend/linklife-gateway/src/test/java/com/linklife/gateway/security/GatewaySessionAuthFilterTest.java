package com.linklife.gateway.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linklife.common.core.contract.SessionKeyContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.data.redis.core.ReactiveHashOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewaySessionAuthFilterTest {

    private static final String NEW_PREFIX = SessionKeyContract.NEW_SESSION_PREFIX;
    private static final String LEGACY_PREFIX = SessionKeyContract.LEGACY_SESSION_PREFIX;

    private ReactiveStringRedisTemplate redisTemplate;
    private ReactiveHashOperations<String, Object, Object> hashOps;
    private GatewaySessionAuthFilter filter;

    @BeforeEach
    void setUp() {
        redisTemplate = mock(ReactiveStringRedisTemplate.class);
        hashOps = mock(ReactiveHashOperations.class);
        when(redisTemplate.opsForHash()).thenReturn(hashOps);
        when(hashOps.get(anyString(), any())).thenReturn(Mono.empty());
        when(redisTemplate.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));
        // 默认：new key 不存在（走 legacy 查询路径）
        when(redisTemplate.hasKey(anyString())).thenReturn(Mono.just(false));
        filter = new GatewaySessionAuthFilter(redisTemplate, new ObjectMapper());
    }

    private ServerWebExchange run(MockServerHttpRequest.BaseBuilder<?> requestBuilder) {
        ServerWebExchange exchange = MockServerWebExchange.from(requestBuilder.build());
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();
        GatewayFilterChain chain = exchange1 -> {
            captured.set(exchange1);
            return Mono.empty();
        };
        filter.filter(exchange, chain).block();
        return captured.get();
    }

    @Test
    void clientSuppliedInternalHeadersAreStripped() {
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/shop/1")
                .header("X-LinkLife-User-Id", "999")
                .header("X-LinkLife-User-Nick", "evil")
                .header("X-LinkLife-Admin", "true"));
        HttpHeaders headers = result.getRequest().getHeaders();
        assertThat(headers.keySet())
                .noneMatch(k -> k.toLowerCase().startsWith("x-linklife-"));
    }

    @Test
    void noTokenProtectedReturns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/me").build());
        filter.filter(exchange, exchange1 -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        String body = exchange.getResponse().getBodyAsString().block();
        assertThat(body).contains("\"success\":false").contains("未登录");
    }

    @Test
    void invalidTokenProtectedReturns401() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/me").header("authorization", "bad-token").build());
        filter.filter(exchange, exchange1 -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void noTokenPublicPassesAnonymously() {
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/shop/1"));
        assertThat(result).isNotNull();
        assertThat(result.getRequest().getHeaders().getFirst("X-LinkLife-User-Id")).isNull();
    }

    @Test
    void invalidTokenPublicPassesAnonymously() {
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/shop/1")
                .header("authorization", "bad-token"));
        assertThat(result).isNotNull();
        assertThat(result.getRequest().getHeaders().getFirst("X-LinkLife-User-Id")).isNull();
    }

    @Test
    void validNewSessionInjectsUserId() {
        when(redisTemplate.hasKey(NEW_PREFIX + "t1")).thenReturn(Mono.just(true));
        when(hashOps.get(NEW_PREFIX + "t1", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "1"));
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/user/me")
                .header("authorization", "t1"));
        assertThat(result.getRequest().getHeaders().getFirst("X-LinkLife-User-Id")).isEqualTo("1");
    }

    @Test
    void validLegacySessionInjectsUserId() {
        when(hashOps.get(LEGACY_PREFIX + "t2", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "2"));
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/user/me")
                .header("authorization", "t2"));
        assertThat(result.getRequest().getHeaders().getFirst("X-LinkLife-User-Id")).isEqualTo("2");
    }

    @Test
    void validPublicSessionStillInjectsUserId() {
        when(redisTemplate.hasKey(NEW_PREFIX + "t3")).thenReturn(Mono.just(true));
        when(hashOps.get(NEW_PREFIX + "t3", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "3"));
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/blog/hot")
                .header("authorization", "t3"));
        assertThat(result.getRequest().getHeaders().getFirst("X-LinkLife-User-Id")).isEqualTo("3");
    }

    @Test
    void validSessionRefreshesTtl() {
        when(redisTemplate.hasKey(NEW_PREFIX + "t4")).thenReturn(Mono.just(true));
        when(hashOps.get(NEW_PREFIX + "t4", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "4"));
        run(MockServerHttpRequest.get("/api/user/me").header("authorization", "t4"));
        verify(redisTemplate).expire(NEW_PREFIX + "t4", Duration.ofMinutes(SessionKeyContract.SESSION_TTL_MINUTES));
    }

    @Test
    void newKeyWinsOverLegacy() {
        when(redisTemplate.hasKey(NEW_PREFIX + "t5")).thenReturn(Mono.just(true));
        when(hashOps.get(NEW_PREFIX + "t5", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "5"));
        when(hashOps.get(LEGACY_PREFIX + "t5", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "6"));
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/user/me")
                .header("authorization", "t5"));
        assertThat(result.getRequest().getHeaders().getFirst("X-LinkLife-User-Id")).isEqualTo("5");
        verify(hashOps, never()).get(LEGACY_PREFIX + "t5", SessionKeyContract.SESSION_USER_ID_FIELD);
        verify(redisTemplate, never()).expire(LEGACY_PREFIX + "t5", Duration.ofMinutes(SessionKeyContract.SESSION_TTL_MINUTES));
    }

    @Test
    void nonPositiveSessionIdFailClosed() {
        when(redisTemplate.hasKey(NEW_PREFIX + "t6")).thenReturn(Mono.just(true));
        when(hashOps.get(NEW_PREFIX + "t6", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "0"));
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/me").header("authorization", "t6").build());
        filter.filter(exchange, exchange1 -> Mono.empty()).block();
        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void nickIconAdminNeverPropagated() {
        when(redisTemplate.hasKey(NEW_PREFIX + "t7")).thenReturn(Mono.just(true));
        when(hashOps.get(NEW_PREFIX + "t7", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "7"));
        ServerWebExchange result = run(MockServerHttpRequest.get("/api/user/me")
                .header("authorization", "t7")
                .header("X-LinkLife-User-Nick", "n")
                .header("X-LinkLife-User-Icon", "i")
                .header("X-LinkLife-Admin", "true"));
        HttpHeaders headers = result.getRequest().getHeaders();
        assertThat(headers.getFirst("X-LinkLife-User-Id")).isEqualTo("7");
        assertThat(headers.getFirst("X-LinkLife-User-Nick")).isNull();
        assertThat(headers.getFirst("X-LinkLife-User-Icon")).isNull();
        assertThat(headers.getFirst("X-LinkLife-Admin")).isNull();
    }

    @Test
    void newKeyExistsWithInvalidIdDoesNotFallbackToLegacy() {
        when(redisTemplate.hasKey(NEW_PREFIX + "t8")).thenReturn(Mono.just(true));
        when(hashOps.get(NEW_PREFIX + "t8", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "0"));
        when(hashOps.get(LEGACY_PREFIX + "t8", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "1"));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/me").header("authorization", "t8").build());
        filter.filter(exchange, exchange1 -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        verify(hashOps, never()).get(LEGACY_PREFIX + "t8", SessionKeyContract.SESSION_USER_ID_FIELD);
        verify(redisTemplate, never()).expire(LEGACY_PREFIX + "t8", Duration.ofMinutes(SessionKeyContract.SESSION_TTL_MINUTES));
    }

    @Test
    void newKeyExistsWithMissingIdFieldDoesNotFallbackToLegacy() {
        when(redisTemplate.hasKey(NEW_PREFIX + "t9")).thenReturn(Mono.just(true));
        when(hashOps.get(LEGACY_PREFIX + "t9", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "1"));

        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/user/me").header("authorization", "t9").build());
        filter.filter(exchange, exchange1 -> Mono.empty()).block();

        assertThat(exchange.getResponse().getStatusCode().value()).isEqualTo(401);
        verify(hashOps, never()).get(LEGACY_PREFIX + "t9", SessionKeyContract.SESSION_USER_ID_FIELD);
    }

    @Test
    void newKeyMissingLegacyValidStillWorks() {
        when(redisTemplate.hasKey(NEW_PREFIX + "t10")).thenReturn(Mono.just(false));
        when(hashOps.get(LEGACY_PREFIX + "t10", SessionKeyContract.SESSION_USER_ID_FIELD))
                .thenReturn(Mono.just((Object) "10"));

        ServerWebExchange result = run(MockServerHttpRequest.get("/api/user/me")
                .header("authorization", "t10"));
        assertThat(result.getRequest().getHeaders().getFirst("X-LinkLife-User-Id")).isEqualTo("10");
        verify(redisTemplate).expire(LEGACY_PREFIX + "t10", Duration.ofMinutes(SessionKeyContract.SESSION_TTL_MINUTES));
    }
}
