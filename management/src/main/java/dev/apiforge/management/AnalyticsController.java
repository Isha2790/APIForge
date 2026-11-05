package dev.apiforge.management;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Reads the hourly roll-ups written by UsageConsumer. Any member of the organization may read them. */
@RestController
@RequestMapping("/organizations/{orgId}/analytics")
public class AnalyticsController {
    public record Summary(long requests, long success, long clientErrors, long serverErrors, long rateLimited,
                          long quotaExceeded, long scopeDenied, double avgLatencyMs, long maxLatencyMs,
                          double errorRate, double rejectedRate) {}

    public record Point(Instant hour, long requests, long success, long clientErrors, long serverErrors,
                        long rateLimited, long quotaExceeded, double avgLatencyMs) {}

    public record ApiRow(UUID apiId, String name, long requests, long rateLimited, long quotaExceeded, long serverErrors,
                         double avgLatencyMs) {}

    private final JdbcTemplate jdbc;
    private final Access access;
    private final Repositories.Apis apis;

    public AnalyticsController(JdbcTemplate jdbc, Access access, Repositories.Apis apis) {
        this.jdbc = jdbc;
        this.access = access;
        this.apis = apis;
    }

    @GetMapping("/summary")
    public Summary summary(Authentication auth, @PathVariable UUID orgId,
                           @RequestParam(defaultValue = "24") int hours, @RequestParam(required = false) UUID apiId) {
        Filter f = filter(auth, orgId, hours, apiId);
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(requests),0) r, COALESCE(SUM(success),0) s, COALESCE(SUM(client_errors),0) c, "
                        + "COALESCE(SUM(server_errors),0) e, COALESCE(SUM(rate_limited),0) rl, COALESCE(SUM(quota_exceeded),0) q, "
                        + "COALESCE(SUM(scope_denied),0) sd, COALESCE(SUM(latency_sum_ms),0) ls, COALESCE(MAX(latency_max_ms),0) lm "
                        + f.where,
                (rs, i) -> {
                    long requests = rs.getLong("r");
                    long serverErrors = rs.getLong("e");
                    long rejected = rs.getLong("rl") + rs.getLong("q") + rs.getLong("sd");
                    return new Summary(requests, rs.getLong("s"), rs.getLong("c"), serverErrors, rs.getLong("rl"),
                            rs.getLong("q"), rs.getLong("sd"), requests == 0 ? 0 : (double) rs.getLong("ls") / requests,
                            rs.getLong("lm"), requests == 0 ? 0 : (double) serverErrors / requests,
                            requests == 0 ? 0 : (double) rejected / requests);
                },
                f.args.toArray());
    }

    @GetMapping("/timeseries")
    public List<Point> timeseries(Authentication auth, @PathVariable UUID orgId,
                                  @RequestParam(defaultValue = "24") int hours, @RequestParam(required = false) UUID apiId) {
        Filter f = filter(auth, orgId, hours, apiId);
        return jdbc.query(
                "SELECT bucket, SUM(requests) r, SUM(success) s, SUM(client_errors) c, SUM(server_errors) e, "
                        + "SUM(rate_limited) rl, SUM(quota_exceeded) q, SUM(latency_sum_ms) ls "
                        + f.where + " GROUP BY bucket ORDER BY bucket",
                (rs, i) -> {
                    long requests = rs.getLong("r");
                    return new Point(rs.getObject("bucket", OffsetDateTime.class).toInstant(), requests, rs.getLong("s"),
                            rs.getLong("c"), rs.getLong("e"), rs.getLong("rl"), rs.getLong("q"),
                            requests == 0 ? 0 : (double) rs.getLong("ls") / requests);
                },
                f.args.toArray());
    }

    @GetMapping("/by-api")
    public List<ApiRow> byApi(Authentication auth, @PathVariable UUID orgId, @RequestParam(defaultValue = "24") int hours) {
        Filter f = filter(auth, orgId, hours, null);
        return jdbc.query(
                "SELECT u.api_id, COALESCE(a.name, '(deleted API)') name, SUM(u.requests) r, SUM(u.rate_limited) rl, "
                        + "SUM(u.quota_exceeded) q, SUM(u.server_errors) e, SUM(u.latency_sum_ms) ls "
                        + "FROM usage_hourly u LEFT JOIN managed_apis a ON a.id = u.api_id "
                        + "WHERE u.org_id = ? AND u.bucket >= date_trunc('hour', now()) - make_interval(hours => ?) "
                        + "GROUP BY u.api_id, a.name ORDER BY r DESC",
                (rs, i) -> {
                    long requests = rs.getLong("r");
                    return new ApiRow(rs.getObject("api_id", UUID.class), rs.getString("name"), requests, rs.getLong("rl"),
                            rs.getLong("q"), rs.getLong("e"), requests == 0 ? 0 : (double) rs.getLong("ls") / requests);
                },
                f.args.toArray());
    }

    private record Filter(String where, List<Object> args) {}

    private Filter filter(Authentication auth, UUID orgId, int hours, UUID apiId) {
        access.role(Access.userId(auth), orgId);
        int h = Math.max(1, Math.min(hours, 720));
        List<Object> args = new ArrayList<>(List.of(orgId, h));
        String where = "FROM usage_hourly WHERE org_id = ? AND bucket >= date_trunc('hour', now()) - make_interval(hours => ?)";
        if (apiId != null) {
            apis.findById(apiId).filter(a -> a.organizationId.equals(orgId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
            where += " AND api_id = ?";
            args.add(apiId);
        }
        return new Filter(where, args);
    }
}
