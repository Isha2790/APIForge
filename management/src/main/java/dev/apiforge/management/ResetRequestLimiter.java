package dev.apiforge.management;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** At most 3 reset emails per address per hour, so the form cannot be used to flood someone's inbox. Fails open if Redis is down. */
@Component
public class ResetRequestLimiter {
    private static final Logger log = LoggerFactory.getLogger(ResetRequestLimiter.class);
    static final int MAX_PER_HOUR = 3;
    private static final Duration WINDOW = Duration.ofHours(1);
    private final StringRedisTemplate redis;

    public ResetRequestLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** True if another email may be sent to this address now. */
    public boolean tryAcquire(String email) {
        try {
            String key = "pwreset:" + email;
            Long count = redis.opsForValue().increment(key);
            if (count != null && count == 1L) redis.expire(key, WINDOW);
            return count == null || count <= MAX_PER_HOUR;
        } catch (RuntimeException e) {
            log.warn("reset limiter unavailable: {}", e.toString());
            return true;
        }
    }
}
