package dev.apiforge.management;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Invalidates the gateway's cached key validations. The gateway caches under "kc:{apiId}:{sha256(rawKey)}",
 * which is exactly {apiId} + the key_hash we store, so we can evict precisely. Entries also carry a short
 * TTL, so a failed eviction can only leave stale data for a bounded time.
 */
@Component
public class KeyCache {
    private static final Logger log = LoggerFactory.getLogger(KeyCache.class);
    private final StringRedisTemplate redis;
    private final Repositories.Keys keys;

    public KeyCache(StringRedisTemplate redis, Repositories.Keys keys) {
        this.redis = redis;
        this.keys = keys;
    }

    public static String cacheKey(UUID apiId, String keyHash) {
        return "kc:" + apiId + ":" + keyHash;
    }

    public void evict(UUID apiId, String... hashes) {
        List<String> redisKeys = new ArrayList<>();
        for (String hash : hashes) {
            if (hash != null) redisKeys.add(cacheKey(apiId, hash));
        }
        if (redisKeys.isEmpty()) return;
        try {
            redis.delete(redisKeys);
        } catch (RuntimeException e) {
            log.warn("cache eviction failed, entries expire by TTL: {}", e.toString());
        }
    }

    /** Every hash (current and previous) that may be cached for this API. */
    public List<String> hashesOf(UUID apiId) {
        List<String> hashes = new ArrayList<>();
        for (Models.ApiKey key : keys.findByApiId(apiId)) {
            hashes.add(key.keyHash);
            if (key.previousKeyHash != null) hashes.add(key.previousKeyHash);
        }
        return hashes;
    }

    public void evictAll(UUID apiId, List<String> hashes) {
        evict(apiId, hashes.toArray(new String[0]));
    }

    public void evictApi(UUID apiId) {
        evictAll(apiId, hashesOf(apiId));
    }
}
