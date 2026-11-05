package dev.apiforge.gateway;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

/** Daily and monthly per-key quotas, checked and consumed atomically in Redis (UTC calendar days/months). */
@Component
class QuotaService {
    private static final Logger log = LoggerFactory.getLogger(QuotaService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    static final int OK = 0;
    static final int DAILY_EXCEEDED = 1;
    static final int MONTHLY_EXCEEDED = 2;

    record Result(int code, long dailyRemaining, long monthlyRemaining) {}

    private final ReactiveStringRedisTemplate redis;
    private final boolean failOpen;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>();

    QuotaService(ReactiveStringRedisTemplate redis, @Value("${app.failure-policy:fail-closed}") String failurePolicy) {
        this.redis = redis;
        this.failOpen = "fail-open".equalsIgnoreCase(failurePolicy);
        this.script.setLocation(new ClassPathResource("quota.lua"));
        this.script.setResultType(List.class);
    }

    Mono<Result> consume(KeyInfo key) {
        if (key.dailyQuota() <= 0 && key.monthlyQuota() <= 0) {
            return Mono.just(new Result(OK, -1, -1));
        }
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<String> keys = List.of("q:d:" + key.keyId() + ":" + today.format(DAY), "q:m:" + key.keyId() + ":" + today.format(MONTH));
        List<String> args = List.of(Long.toString(Math.max(0, key.dailyQuota())), Long.toString(Math.max(0, key.monthlyQuota())),
                Long.toString(2 * 86_400L), Long.toString(33 * 86_400L));
        return redis.execute(script, keys, args)
                .next()
                .map(r -> new Result(((Number) r.get(0)).intValue(), ((Number) r.get(1)).longValue(), ((Number) r.get(2)).longValue()))
                .switchIfEmpty(Mono.error(new IllegalStateException("empty quota reply")))
                .onErrorResume(e -> {
                    log.warn("quota check error ({}): {}", failOpen ? "fail-open" : "fail-closed", e.toString());
                    if (failOpen) return Mono.just(new Result(OK, -1, -1));
                    return Mono.error(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Quota service unavailable"));
                });
    }
}
