package com.wuxx.exchangeclear.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientIpForwardFilterTest {

    private final ClientIpForwardFilter filter = new ClientIpForwardFilter();

    @Test
    void shouldGenerateRequestIdWhenMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/download/files/FILE001")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .build();
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        filter.filter(MockServerWebExchange.from(request), exchange -> {
            captured.set(exchange);
            return Mono.empty();
        }).block();

        String requestId = captured.get().getRequest().getHeaders().getFirst("X-Request-Id");
        assertNotNull(requestId);
        assertTrue(requestId.startsWith("REQ"));
        assertEquals("127.0.0.1", captured.get().getRequest().getHeaders().getFirst("X-Client-IP"));
    }

    @Test
    void shouldKeepIncomingRequestIdAndForwardedClientIp() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/download/files/FILE001")
                .header("X-Request-Id", "REQ-EXISTS")
                .header("X-Forwarded-For", "10.0.0.1, 10.0.0.2")
                .build();
        AtomicReference<ServerWebExchange> captured = new AtomicReference<>();

        filter.filter(MockServerWebExchange.from(request), exchange -> {
            captured.set(exchange);
            return Mono.empty();
        }).block();

        assertEquals("REQ-EXISTS", captured.get().getRequest().getHeaders().getFirst("X-Request-Id"));
        assertEquals("10.0.0.1", captured.get().getRequest().getHeaders().getFirst("X-Client-IP"));
    }
}
