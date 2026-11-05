package dev.apiforge.gateway;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

/**
 * The request pipeline for /proxy/{apiId}/**:
 * key lookup (cache, then management) -> method/path scope -> token-bucket rate limit -> daily/monthly quota ->
 * upstream SSRF re-check -> proxy -> usage event + metrics.
 * Only validation/limiter failures become 503 (or fail-open for limiter/quota); upstream errors keep Gateway's
 * normal 502/504 handling.
 */
@Configuration
class GatewayRoutes {
    private final KeyDirectory directory;
    private final RateLimiter limiter;
    private final QuotaService quotas;
    private final UpstreamGuard guard;
    private final UsagePublisher usage;
    private final RequestMetrics metrics;

    GatewayRoutes(KeyDirectory directory, RateLimiter limiter, QuotaService quotas, UpstreamGuard guard,
                  UsagePublisher usage, RequestMetrics metrics) {
        this.directory = directory;
        this.limiter = limiter;
        this.quotas = quotas;
        this.guard = guard;
        this.usage = usage;
        this.metrics = metrics;
    }

    @Bean
    RouteLocator routes(RouteLocatorBuilder builder) {
        // Order 10001 runs after RouteToRequestUrlFilter (10000), so the target URL we set wins.
        return builder.routes()
                .route("customer-api", r -> r.path("/proxy/{apiId}/**")
                        .filters(f -> f.filter((exchange, chain) -> handle(exchange, chain), 10001))
                        .uri("no://op"))
                .build();
    }

    private Mono<Void> handle(ServerWebExchange exchange, GatewayFilterChain chain) {
        long start = System.nanoTime();
        String[] parts = exchange.getRequest().getURI().getRawPath().split("/", 4);
        if (parts.length < 3) return reject(exchange, 400);
        String apiId;
        try {
            // canonical lower-case form: the cache key must match the one management evicts
            apiId = UUID.fromString(parts[2]).toString();
        } catch (IllegalArgumentException e) {
            return reject(exchange, 400);
        }
        String suffix = parts.length == 4 ? "/" + parts[3] : "/";
        if (isSuspicious(suffix)) return reject(exchange, 400);

        String rawKey = exchange.getRequest().getHeaders().getFirst("X-API-Key");
        if (rawKey == null || rawKey.isBlank()) {
            metrics.request("missing_key", start);
            return reject(exchange, 401);
        }
        String method = exchange.getRequest().getMethod().name();

        return directory.lookup(apiId, rawKey, clientIp(exchange))
                .flatMap(found -> {
                    if (found.isEmpty()) {
                        metrics.request("invalid_key", start);
                        return reject(exchange, 401);
                    }
                    KeyInfo key = found.get();
                    if (!key.allowsMethod(method) || !key.allowsPath(suffix)) {
                        return finish(exchange, key, "SCOPE_DENIED", 403, start);
                    }
                    return limiter.acquire(key).flatMap(rate -> {
                        HttpHeaders headers = exchange.getResponse().getHeaders();
                        headers.set("X-RateLimit-Limit", Integer.toString(key.requestsPerMinute()));
                        if (rate.remaining() >= 0) headers.set("X-RateLimit-Remaining", Long.toString(rate.remaining()));
                        if (!rate.allowed()) {
                            headers.set(HttpHeaders.RETRY_AFTER, Integer.toString(Math.max(1, (int) Math.ceil(60.0 / key.requestsPerMinute()))));
                            return finish(exchange, key, "RATE_LIMITED", 429, start);
                        }
                        return quotas.consume(key).flatMap(quota -> {
                            if (quota.code() != QuotaService.OK) {
                                headers.set("X-Quota-Exceeded", quota.code() == QuotaService.DAILY_EXCEEDED ? "daily" : "monthly");
                                return finish(exchange, key, "QUOTA_EXCEEDED", 429, start);
                            }
                            if (quota.dailyRemaining() >= 0) headers.set("X-Quota-Daily-Remaining", Long.toString(quota.dailyRemaining()));
                            if (quota.monthlyRemaining() >= 0) headers.set("X-Quota-Monthly-Remaining", Long.toString(quota.monthlyRemaining()));
                            return guard.isAllowed(key.upstreamUrl()).flatMap(ok -> ok
                                    ? forward(exchange, chain, key, suffix, start)
                                    : finish(exchange, key, "UPSTREAM_BLOCKED", 502, start));
                        });
                    });
                })
                .doOnError(ResponseStatusException.class, e -> metrics.request("unavailable", start));
    }

    private Mono<Void> forward(ServerWebExchange exchange, GatewayFilterChain chain, KeyInfo key, String suffix, long start) {
        String query = exchange.getRequest().getURI().getRawQuery();
        String base = key.upstreamUrl().replaceAll("/+$", "");
        URI target = URI.create(base + suffix + (query == null ? "" : "?" + query));
        exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, target);
        ServerWebExchange mutated = exchange.mutate()
                .request(req -> req.headers(h -> {
                    h.remove("X-API-Key");   // never leak the credential to the upstream
                    h.remove("X-Internal-Secret");
                    h.remove(HttpHeaders.AUTHORIZATION);
                }))
                .build();
        return chain.filter(mutated).doFinally(signal -> {
            HttpStatusCode code = mutated.getResponse().getStatusCode();
            int status = code != null ? code.value() : (signal == SignalType.ON_ERROR ? 502 : signal == SignalType.CANCEL ? 499 : 200);
            record(key, "PROXIED", status, start);
        });
    }

    private Mono<Void> finish(ServerWebExchange exchange, KeyInfo key, String outcome, int status, long start) {
        record(key, outcome, status, start);
        return reject(exchange, status);
    }

    private void record(KeyInfo key, String outcome, int status, long start) {
        metrics.request(outcome, start);
        usage.publish(key, outcome, status, start);
    }

    private static Mono<Void> reject(ServerWebExchange exchange, int status) {
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(status));
        return exchange.getResponse().setComplete();
    }

    /** Refuse path tricks that could slip past a scope prefix once the upstream normalizes the path. */
    static boolean isSuspicious(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("/../") || lower.endsWith("/..") || lower.contains("%2e") || lower.contains("\\")
                || lower.contains("%5c") || lower.contains("//");
    }

    /** Direct peer address. X-Forwarded-For is deliberately ignored: it is client-controlled unless a trusted proxy sets it. */
    private static String clientIp(ServerWebExchange exchange) {
        InetSocketAddress address = exchange.getRequest().getRemoteAddress();
        return address == null || address.getAddress() == null ? "unknown" : address.getAddress().getHostAddress();
    }
}
