package dev.apiforge.management;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the "usage:events" Redis Stream (written by gateways) into hourly roll-ups in PostgreSQL.
 * Delivery is at-least-once: entries are acknowledged only after the database transaction commits, and
 * entries that were delivered but never acknowledged (e.g. after a crash) are re-read first on the next poll.
 * Use a distinct app.usage.consumer-name per management instance.
 */
@Component
public class UsageConsumer {
    private static final Logger log = LoggerFactory.getLogger(UsageConsumer.class);
    static final String STREAM = "usage:events";
    static final String GROUP = "management";
    private static final int BATCH = 500;
    private static final long KEEP_AT_MOST = 100_000;

    private static final String UPSERT = "INSERT INTO usage_hourly (org_id, api_id, key_id, bucket, requests, success, client_errors, "
            + "server_errors, rate_limited, quota_exceeded, scope_denied, latency_sum_ms, latency_max_ms) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?) "
            + "ON CONFLICT (key_id, bucket) DO UPDATE SET "
            + "requests = usage_hourly.requests + EXCLUDED.requests, "
            + "success = usage_hourly.success + EXCLUDED.success, "
            + "client_errors = usage_hourly.client_errors + EXCLUDED.client_errors, "
            + "server_errors = usage_hourly.server_errors + EXCLUDED.server_errors, "
            + "rate_limited = usage_hourly.rate_limited + EXCLUDED.rate_limited, "
            + "quota_exceeded = usage_hourly.quota_exceeded + EXCLUDED.quota_exceeded, "
            + "scope_denied = usage_hourly.scope_denied + EXCLUDED.scope_denied, "
            + "latency_sum_ms = usage_hourly.latency_sum_ms + EXCLUDED.latency_sum_ms, "
            + "latency_max_ms = GREATEST(usage_hourly.latency_max_ms, EXCLUDED.latency_max_ms)";

    private record Bucket(UUID orgId, UUID apiId, UUID keyId, Instant hour) {}

    private static final class Totals {
        long requests, success, clientErrors, serverErrors, rateLimited, quotaExceeded, scopeDenied, latencySum;
        int latencyMax;
    }

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Counter processed;
    private final String consumerName;
    private volatile boolean groupReady;

    public UsageConsumer(StringRedisTemplate redis, JdbcTemplate jdbc, TransactionTemplate tx, MeterRegistry registry,
                         @Value("${app.usage.consumer-name}") String consumerName) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.tx = tx;
        this.processed = registry.counter("apiforge.usage.events.processed");
        this.consumerName = consumerName;
    }

    /** synchronized: the scheduler and tests may both call it, and one consumer name must never read twice concurrently. */
    @Scheduled(fixedDelayString = "${app.usage.poll-ms:1000}")
    public synchronized void poll() {
        try {
            ensureGroup();
            while (drainOnce() >= BATCH) {
                // keep draining while batches come back full
            }
        } catch (Exception e) {
            groupReady = false;
            log.warn("usage consumer error: {}", e.toString());
        }
    }

    private int drainOnce() {
        List<MapRecord<String, Object, Object>> records = read(ReadOffset.from("0"));   // delivered earlier, never acknowledged
        if (records == null || records.isEmpty()) {
            records = read(ReadOffset.lastConsumed());                                  // new entries
        }
        if (records == null || records.isEmpty()) return 0;
        handle(records);
        return records.size();
    }

    @SuppressWarnings("unchecked")
    private List<MapRecord<String, Object, Object>> read(ReadOffset offset) {
        return redis.opsForStream().read(Consumer.from(GROUP, consumerName), StreamReadOptions.empty().count(BATCH),
                StreamOffset.create(STREAM, offset));
    }

    private void handle(List<MapRecord<String, Object, Object>> records) {
        Map<Bucket, Totals> totals = new HashMap<>();
        List<RecordId> ids = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            ids.add(record.getId());
            try {
                accumulate(totals, record.getValue());
            } catch (RuntimeException malformed) {
                log.warn("skipping malformed usage event {}: {}", record.getId(), malformed.toString());
            }
        }
        if (!totals.isEmpty()) {
            tx.executeWithoutResult(status -> upsert(totals));
        }
        redis.opsForStream().acknowledge(STREAM, GROUP, ids.toArray(new RecordId[0]));
        redis.opsForStream().trim(STREAM, KEEP_AT_MOST, true);
        processed.increment(ids.size());
    }

    private static void accumulate(Map<Bucket, Totals> totals, Map<Object, Object> event) {
        UUID orgId = UUID.fromString(text(event, "org"));
        UUID apiId = UUID.fromString(text(event, "api"));
        UUID keyId = UUID.fromString(text(event, "key"));
        String outcome = text(event, "outcome");
        int status = Integer.parseInt(text(event, "status"));
        long latency = Math.max(0, Long.parseLong(text(event, "latency")));
        Instant hour = Instant.ofEpochMilli(Long.parseLong(text(event, "ts"))).truncatedTo(ChronoUnit.HOURS);

        Totals t = totals.computeIfAbsent(new Bucket(orgId, apiId, keyId, hour), b -> new Totals());
        t.requests++;
        t.latencySum += latency;
        t.latencyMax = (int) Math.max(t.latencyMax, Math.min(latency, Integer.MAX_VALUE));
        switch (outcome) {
            case "RATE_LIMITED" -> t.rateLimited++;
            case "QUOTA_EXCEEDED" -> t.quotaExceeded++;
            case "SCOPE_DENIED" -> t.scopeDenied++;
            case "UPSTREAM_BLOCKED" -> t.serverErrors++;
            default -> {   // PROXIED: classify by the upstream's status code
                if (status >= 500) t.serverErrors++;
                else if (status >= 400) t.clientErrors++;
                else t.success++;
            }
        }
    }

    private void upsert(Map<Bucket, Totals> totals) {
        List<Object[]> rows = new ArrayList<>();
        totals.forEach((b, t) -> rows.add(new Object[]{b.orgId(), b.apiId(), b.keyId(), Timestamp.from(b.hour()),
                t.requests, t.success, t.clientErrors, t.serverErrors, t.rateLimited, t.quotaExceeded, t.scopeDenied,
                t.latencySum, t.latencyMax}));
        jdbc.batchUpdate(UPSERT, rows);
    }

    private static String text(Map<Object, Object> event, String field) {
        Object value = event.get(field);
        if (value == null) throw new IllegalArgumentException("missing field " + field);
        return value.toString();
    }

    private void ensureGroup() {
        if (groupReady) return;
        try {
            redis.execute((RedisCallback<Object>) connection -> connection.execute("XGROUP", bytes("CREATE"), bytes(STREAM),
                    bytes(GROUP), bytes("0"), bytes("MKSTREAM")));
        } catch (Exception e) {
            boolean alreadyExists = String.valueOf(e.getMessage()).contains("BUSYGROUP") || String.valueOf(e.getCause()).contains("BUSYGROUP");
            if (!alreadyExists) throw e;
        }
        groupReady = true;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
