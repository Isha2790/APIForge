package dev.apiforge.gateway;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Fire-and-forget: analytics must never slow down or break the request path. */
@Component
class UsagePublisher {
    private static final Logger log = LoggerFactory.getLogger(UsagePublisher.class);
    static final String STREAM = "usage:events";

    private final ReactiveStringRedisTemplate redis;
    private final DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    private final String maxLength;

    UsagePublisher(ReactiveStringRedisTemplate redis, @Value("${app.usage-stream-maxlen:200000}") long maxLength) {
        this.redis = redis;
        this.maxLength = Long.toString(maxLength);
        this.script.setLocation(new ClassPathResource("usage_xadd.lua"));
        this.script.setResultType(Long.class);
    }

    void publish(KeyInfo key, String outcome, int status, long startNanos) {
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000;
        List<String> args = List.of(maxLength,
                "org", key.orgId(), "api", key.apiId(), "key", key.keyId(),
                "outcome", outcome, "status", Integer.toString(status),
                "latency", Long.toString(latencyMs), "ts", Long.toString(System.currentTimeMillis()));
        redis.execute(script, List.of(STREAM), args)
                .subscribe(ok -> { }, e -> log.warn("usage event dropped: {}", e.toString()));
    }
}
