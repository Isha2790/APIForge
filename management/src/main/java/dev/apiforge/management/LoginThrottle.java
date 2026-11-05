package dev.apiforge.management;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Locks an account for 15 minutes after 5 failed logins. Fails open if Redis is down (login stays available). */
@Component
public class LoginThrottle {
    private static final Logger log = LoggerFactory.getLogger(LoginThrottle.class);
    static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final StringRedisTemplate redis;

    public LoginThrottle(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isLocked(String email) {
        try {
            String value = redis.opsForValue().get(key(email));
            return value != null && Long.parseLong(value) >= MAX_FAILURES;
        } catch (RuntimeException e) {
            log.warn("login throttle unavailable: {}", e.toString());
            return false;
        }
    }

    public void recordFailure(String email) {
        try {
            Long count = redis.opsForValue().increment(key(email));
            if (count != null && count == 1L) redis.expire(key(email), WINDOW);
        } catch (RuntimeException e) {
            log.warn("login throttle unavailable: {}", e.toString());
        }
    }

    public void reset(String email) {
        try {
            redis.delete(key(email));
        } catch (RuntimeException e) {
            log.warn("login throttle unavailable: {}", e.toString());
        }
    }

    private static String key(String email) {
        return "loginfail:" + email;
    }
}
