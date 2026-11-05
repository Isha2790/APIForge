package dev.apiforge.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * Resolves an API key to its configuration. Order: Redis cache -> management service.
 * Cache key "kc:{apiId}:{sha256(key)}" matches the key_hash stored by management, so management can evict
 * exactly the entries it changed. Positive entries live for the TTL management returns (never longer than the
 * key stays valid); unknown keys are cached for a few seconds so repeated bad keys never reach the database.
 * If management is down, cached keys keep working.
 */
@Component
class KeyDirectory {
    private static final Logger log = LoggerFactory.getLogger(KeyDirectory.class);
    private static final String INVALID = "INVALID";
    private static final int NEGATIVE_TTL_SECONDS = 10;
    private static final long MAX_INVALID_PER_MINUTE = 30;

    private final ReactiveStringRedisTemplate redis;
    private final WebClient management;
    private final ObjectMapper mapper;
    private final RequestMetrics metrics;
    private final String internalSecret;

    KeyDirectory(ReactiveStringRedisTemplate redis, ObjectMapper mapper, RequestMetrics metrics,
                 @Value("${app.management-url}") String managementUrl,
                 @Value("${app.internal-secret}") String internalSecret) {
        this.redis = redis;
        this.mapper = mapper;
        this.metrics = metrics;
        this.internalSecret = internalSecret;
        this.management = WebClient.builder().baseUrl(managementUrl).build();
    }

    /** Empty Optional = unknown, revoked or expired key. Errors are ResponseStatusException (429 / 503). */
    Mono<Optional<KeyInfo>> lookup(String apiId, String rawKey, String clientIp) {
        String cacheKey = "kc:" + apiId + ":" + Hashing.sha256(rawKey);
        return readCache(cacheKey).switchIfEmpty(Mono.defer(() ->
                checkInvalidKeyBudget(clientIp).then(Mono.defer(() -> loadFromManagement(apiId, rawKey, cacheKey, clientIp)))));
    }

    private Mono<Optional<KeyInfo>> readCache(String cacheKey) {
        return redis.opsForValue().get(cacheKey)
                .flatMap(value -> {
                    if (INVALID.equals(value)) {
                        metrics.cache("negative_hit");
                        return Mono.just(Optional.<KeyInfo>empty());
                    }
                    try {
                        KeyInfo info = mapper.readValue(value, KeyInfo.class);
                        metrics.cache("hit");
                        return Mono.just(Optional.of(info));
                    } catch (Exception e) {
                        return Mono.<Optional<KeyInfo>>empty();   // unreadable entry: treat as a miss
                    }
                })
                .onErrorResume(e -> {
                    log.warn("cache read failed, falling back to management: {}", e.toString());
                    return Mono.<Optional<KeyInfo>>empty();
                });
    }

    private Mono<Optional<KeyInfo>> loadFromManagement(String apiId, String rawKey, String cacheKey, String clientIp) {
        metrics.cache("miss");
        return management.post()
                .uri("/internal/validate/{id}", apiId)
                .header("X-Internal-Secret", internalSecret)
                .header("X-API-Key", rawKey)
                .retrieve()
                .bodyToMono(KeyInfo.class)
                .timeout(Duration.ofSeconds(2))
                .retryWhen(Retry.backoff(1, Duration.ofMillis(100)).filter(e -> !(e instanceof WebClientResponseException)))
                .flatMap(info -> cachePut(cacheKey, toJson(info), Math.max(1, info.cacheTtlSeconds())).thenReturn(Optional.of(info)))
                .onErrorResume(WebClientResponseException.class, e -> {
                    int status = e.getStatusCode().value();
                    if (status == 401 || status == 404) {
                        return cachePut(cacheKey, INVALID, NEGATIVE_TTL_SECONDS)
                                .then(recordInvalidKey(clientIp))
                                .thenReturn(Optional.<KeyInfo>empty());
                    }
                    return Mono.error(e);
                })
                .onErrorMap(e -> !(e instanceof ResponseStatusException), e -> {
                    log.warn("key validation failed: {}", e.toString());
                    return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Key validation unavailable");
                });
    }

    private Mono<Boolean> cachePut(String key, String value, int ttlSeconds) {
        return redis.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds)).onErrorReturn(false);
    }

    private String toJson(KeyInfo info) {
        try {
            return mapper.writeValueAsString(info);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Callers that keep sending unknown keys are throttled before they can hit the management database. */
    private Mono<Void> checkInvalidKeyBudget(String clientIp) {
        return redis.opsForValue().get("authfail:" + clientIp)
                .map(Long::parseLong)
                .defaultIfEmpty(0L)
                .onErrorReturn(0L)
                .flatMap(count -> count >= MAX_INVALID_PER_MINUTE
                        ? Mono.<Void>error(new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many invalid API keys"))
                        : Mono.<Void>empty());
    }

    private Mono<Void> recordInvalidKey(String clientIp) {
        String key = "authfail:" + clientIp;
        return redis.opsForValue().increment(key)
                .flatMap(count -> count == 1L ? redis.expire(key, Duration.ofMinutes(1)).then() : Mono.<Void>empty())
                .onErrorResume(e -> Mono.<Void>empty());
    }
}
