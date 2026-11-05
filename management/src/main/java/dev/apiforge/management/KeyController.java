package dev.apiforge.management;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/apis/{apiId}/keys")
public class KeyController {
    /** Every field is optional; null means "leave as is" (update) or "use the default" (create). */
    public record KeyOptions(
            @Size(max = 100) String name,
            @Min(1) @Max(100000) Integer requestsPerMinute,
            @Min(0) Long dailyQuota,
            @Min(0) Long monthlyQuota,
            @Pattern(regexp = "^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)(,(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS))*$") String allowedMethods,
            @Pattern(regexp = "^/[A-Za-z0-9._~/-]*$") @Size(max = 200) String pathPrefix,
            @Min(1) @Max(3650) Integer expiresInDays) {}

    public record RotateRequest(@Min(0) @Max(10080) Integer graceMinutes) {}

    public record KeyView(UUID id, String name, String prefix, boolean revoked, Integer requestsPerMinute, long dailyQuota,
                          long monthlyQuota, String allowedMethods, String pathPrefix, Instant expiresAt,
                          Instant previousKeyValidUntil) {
        static KeyView of(Models.ApiKey k) {
            return new KeyView(k.id, k.name, k.prefix, k.revoked, k.requestsPerMinute, k.dailyQuota, k.monthlyQuota,
                    k.allowedMethods, k.pathPrefix, k.expiresAt, k.previousKeyHash == null ? null : k.previousExpiresAt);
        }
    }

    /** The only response that ever contains the plaintext key. */
    public record IssuedKey(UUID id, String key, KeyView details, String note) {}

    public record KeyUsage(long dailyUsed, long dailyLimit, long monthlyUsed, long monthlyLimit) {}

    static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyyMM");
    private static final String NOTE = "Store this key now; it cannot be shown again.";

    private final Repositories.Keys keys;
    private final Access access;
    private final KeyCache cache;
    private final StringRedisTemplate redis;

    public KeyController(Repositories.Keys keys, Access access, KeyCache cache, StringRedisTemplate redis) {
        this.keys = keys;
        this.access = access;
        this.cache = cache;
        this.redis = redis;
    }

    @PostMapping
    public IssuedKey create(Authentication auth, @PathVariable UUID apiId,
                            @Valid @RequestBody(required = false) KeyOptions body) {
        Models.ManagedApi api = access.api(Access.userId(auth), apiId, Role.OWNER, Role.ADMIN);
        String raw = Hashing.newApiKey();
        Models.ApiKey key = new Models.ApiKey(api.id, "default", raw.substring(0, 12), Hashing.sha256(raw));
        apply(key, body);
        keys.save(key);
        return new IssuedKey(key.id, raw, KeyView.of(key), NOTE);
    }

    @GetMapping
    public List<KeyView> list(Authentication auth, @PathVariable UUID apiId) {
        access.api(Access.userId(auth), apiId);
        return keys.findByApiId(apiId).stream().map(KeyView::of).toList();
    }

    @PatchMapping("/{keyId}")
    public KeyView update(Authentication auth, @PathVariable UUID apiId, @PathVariable UUID keyId,
                          @Valid @RequestBody KeyOptions body) {
        access.api(Access.userId(auth), apiId, Role.OWNER, Role.ADMIN);
        Models.ApiKey key = find(apiId, keyId);
        apply(key, body);
        keys.save(key);
        cache.evict(apiId, key.keyHash, key.previousKeyHash);
        return KeyView.of(key);
    }

    /** New secret, same settings. The old secret keeps working for graceMinutes (default 60; 0 = cut off now). */
    @PostMapping("/{keyId}/rotate")
    public IssuedKey rotate(Authentication auth, @PathVariable UUID apiId, @PathVariable UUID keyId,
                            @Valid @RequestBody(required = false) RotateRequest body) {
        access.api(Access.userId(auth), apiId, Role.OWNER, Role.ADMIN);
        Models.ApiKey key = find(apiId, keyId);
        if (key.revoked) throw new ResponseStatusException(HttpStatus.CONFLICT, "Key is revoked");
        int grace = body == null || body.graceMinutes() == null ? 60 : body.graceMinutes();

        String oldHash = key.keyHash;
        String olderHash = key.previousKeyHash;
        String raw = Hashing.newApiKey();
        key.keyHash = Hashing.sha256(raw);
        key.prefix = raw.substring(0, 12);
        key.previousKeyHash = grace > 0 ? oldHash : null;
        key.previousExpiresAt = grace > 0 ? Instant.now().plus(grace, ChronoUnit.MINUTES) : null;
        keys.save(key);
        cache.evict(apiId, oldHash, olderHash);
        return new IssuedKey(key.id, raw, KeyView.of(key), NOTE);
    }

    @DeleteMapping("/{keyId}")
    public KeyView revoke(Authentication auth, @PathVariable UUID apiId, @PathVariable UUID keyId) {
        access.api(Access.userId(auth), apiId, Role.OWNER, Role.ADMIN);
        Models.ApiKey key = find(apiId, keyId);
        String current = key.keyHash;
        String previous = key.previousKeyHash;
        key.revoked = true;
        key.previousKeyHash = null;
        key.previousExpiresAt = null;
        keys.save(key);
        cache.evict(apiId, current, previous);
        return KeyView.of(key);
    }

    /** Current quota consumption, read from the same Redis counters the gateway increments. */
    @GetMapping("/{keyId}/usage")
    public KeyUsage usage(Authentication auth, @PathVariable UUID apiId, @PathVariable UUID keyId) {
        access.api(Access.userId(auth), apiId);
        Models.ApiKey key = find(apiId, keyId);
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        return new KeyUsage(counter("q:d:" + key.id + ":" + today.format(DAY)), key.dailyQuota,
                counter("q:m:" + key.id + ":" + today.format(MONTH)), key.monthlyQuota);
    }

    private long counter(String redisKey) {
        try {
            String value = redis.opsForValue().get(redisKey);
            return value == null ? 0 : Long.parseLong(value);
        } catch (RuntimeException e) {
            return -1;   // Redis unavailable: unknown
        }
    }

    private Models.ApiKey find(UUID apiId, UUID keyId) {
        return keys.findByIdAndApiId(keyId, apiId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static void apply(Models.ApiKey key, KeyOptions o) {
        if (o == null) return;
        if (o.name() != null && !o.name().isBlank()) key.name = o.name().trim();
        if (o.requestsPerMinute() != null) key.requestsPerMinute = o.requestsPerMinute();
        if (o.dailyQuota() != null) key.dailyQuota = o.dailyQuota();
        if (o.monthlyQuota() != null) key.monthlyQuota = o.monthlyQuota();
        if (o.allowedMethods() != null) key.allowedMethods = o.allowedMethods();
        if (o.pathPrefix() != null) key.pathPrefix = o.pathPrefix();
        if (o.expiresInDays() != null) key.expiresAt = Instant.now().plus(o.expiresInDays(), ChronoUnit.DAYS);
    }
}
