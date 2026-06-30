package com.wuxx.exchangeclear.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.UUID;

@Component
public class ClientIpForwardFilter implements GlobalFilter, Ordered {

    private static final String CLIENT_IP_HEADER = "X-Client-IP";

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private static final String REAL_IP_HEADER = "X-Real-IP";

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    private static final String REQUEST_ID_PREFIX = "REQ";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String clientIp = resolveClientIp(exchange.getRequest());
        String requestId = resolveRequestId(exchange.getRequest());

        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> {
                    headers.set(REQUEST_ID_HEADER, requestId);
                    if (StringUtils.hasText(clientIp)) {
                        headers.set(CLIENT_IP_HEADER, clientIp);
                    }
                })
                .build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private String resolveClientIp(ServerHttpRequest request) {
        String forwardedFor = request.getHeaders().getFirst(FORWARDED_FOR_HEADER);
        if (StringUtils.hasText(forwardedFor)) {
            return forwardedFor.split(",")[0].trim();
        }

        String realIp = request.getHeaders().getFirst(REAL_IP_HEADER);
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }

        InetSocketAddress remoteAddress = request.getRemoteAddress();
        if (remoteAddress == null || remoteAddress.getAddress() == null) {
            return null;
        }
        return remoteAddress.getAddress().getHostAddress();
    }

    private String resolveRequestId(ServerHttpRequest request) {
        String requestId = request.getHeaders().getFirst(REQUEST_ID_HEADER);
        if (StringUtils.hasText(requestId)) {
            return requestId.trim();
        }
        return REQUEST_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }
}
