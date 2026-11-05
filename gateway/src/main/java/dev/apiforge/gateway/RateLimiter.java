package dev.apiforge.gateway;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Distributed token bucket: one atomic Lua call per request, shared by every gateway instance. */
@Component
class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);

    record Result(boolean allowed, long remaining) {}

    private final ReactiveStringRedisTemplate redis;
    private final boolean failOpen;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();

    RateLimiter(ReactiveStringRedisTemplate redis, @Value("${app.failure-policy:fail-closed}") String failurePolicy) {
        this.redis = redis;
        this.failOpen = "fail-open".equalsIgnoreCase(failurePolicy);
        this.script.setLocation(new ClassPathResource("rate_limit.lua"));
        this.script.setResultType(List.class);
    }

    Mono<Result> acquire(KeyInfo key) {
        return redis.execute(script, List.of("rate:" + key.keyId()), List.of(Integer.toString(key.requestsPerMinute())))
                .next()
                .map(r -> new Result(((Number) r.get(0)).longValue() == 1L, ((Number) r.get(1)).longValue()))
                .switchIfEmpty(Mono.error(new IllegalStateException("empty limiter reply")))
                .onErrorResume(e -> {
                    log.warn("rate limiter error ({}): {}", failOpen ? "fail-open" : "fail-closed", e.toString());
                    if (failOpen) return Mono.just(new Result(true, -1));
                    return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Rate limiter unavailable"));
                });
    }
}
