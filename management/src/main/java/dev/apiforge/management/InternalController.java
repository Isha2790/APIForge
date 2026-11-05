package dev.apiforge.management;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Called only by gateways (shared secret). Never expose this path publicly. */
@RestController
@RequestMapping("/internal")
public class InternalController {
    public record ValidationResponse(String keyId, String orgId, String apiId, String upstreamUrl, int requestsPerMinute,
                                     long dailyQuota, long monthlyQuota, String allowedMethods, String pathPrefix,
                                     int cacheTtlSeconds) {}

    private final Repositories.Apis apis;
    private final Repositories.Keys keys;
    private final String internalSecret;
    private final int maxCacheTtl;

    public InternalController(Repositories.Apis apis, Repositories.Keys keys,
                              @Value("${app.internal-secret}") String internalSecret,
                              @Value("${app.cache-ttl-seconds:60}") int maxCacheTtl) {
        this.apis = apis;
        this.keys = keys;
        this.internalSecret = internalSecret;
        this.maxCacheTtl = maxCacheTtl;
    }

    @PostMapping("/validate/{apiId}")
    public ValidationResponse validate(@PathVariable UUID apiId,
                                       @RequestHeader("X-Internal-Secret") String secret,
                                       @RequestHeader("X-API-Key") String rawKey) {
        if (!MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), internalSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        Models.ManagedApi api = apis.findById(apiId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        String hash = Hashing.sha256(rawKey);
        Instant now = Instant.now();
        Models.ApiKey key = keys.findValid(apiId, hash, now)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (key.expiresAt != null && !key.expiresAt.isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }

        // The gateway may cache this answer, but never past the moment it would stop being true.
        long ttl = maxCacheTtl;
        if (!hash.equals(key.keyHash) && key.previousExpiresAt != null) {
            ttl = Math.min(ttl, Duration.between(now, key.previousExpiresAt).toSeconds());
        }
        if (key.expiresAt != null) {
            ttl = Math.min(ttl, Duration.between(now, key.expiresAt).toSeconds());
        }
        int effectiveRate = key.requestsPerMinute != null ? key.requestsPerMinute : api.requestsPerMinute;
        return new ValidationResponse(key.id.toString(), api.organizationId.toString(), api.id.toString(), api.upstreamUrl,
                effectiveRate, key.dailyQuota, key.monthlyQuota, key.allowedMethods, key.pathPrefix, (int) Math.max(1, ttl));
    }
}
