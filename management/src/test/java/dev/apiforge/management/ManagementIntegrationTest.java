package dev.apiforge.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Boots the whole service on real Postgres and Redis. Proves: Flyway + Hibernate schema validation pass, path
 * variables bind, roles and tenant isolation hold, invitations work, rotation/revocation/cache eviction work,
 * and the Redis Streams worker produces correct analytics. Skipped automatically without Docker.
 */
@SpringBootTest(properties = {"app.ssrf.allowed-private-hosts=demo-api", "app.usage.poll-ms=3600000"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ManagementIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final String INTERNAL = "development-internal-secret";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired StringRedisTemplate stringRedis;
    @Autowired UsageConsumer usageConsumer;

    // ---- helpers ----

    private String field(String body, String name) throws Exception {
        return json.readTree(body).get(name).asText();
    }

    private String register(String email) throws Exception {
        String body = mvc.perform(post("/auth/register").contentType(APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"StrongPassword123!\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return "Bearer " + field(body, "token");
    }

    private String send(MockHttpServletRequestBuilder request, String auth, String content, int expectedStatus) throws Exception {
        MockHttpServletRequestBuilder builder = request.header("Authorization", auth).contentType(APPLICATION_JSON);
        if (content != null) builder = builder.content(content);
        return mvc.perform(builder).andExpect(status().is(expectedStatus)).andReturn().getResponse().getContentAsString();
    }

    private String createOrgAndApi(String owner, String name) throws Exception {
        String org = field(send(post("/organizations"), owner, "{\"name\":\"" + name + "\"}", 200), "id");
        return org;
    }

    private String createApi(String auth, String orgId) throws Exception {
        return field(send(post("/organizations/" + orgId + "/apis"), auth,
                "{\"name\":\"Echo\",\"upstreamUrl\":\"http://demo-api:5678\",\"requestsPerMinute\":5}", 200), "id");
    }

    private MockHttpServletRequestBuilder validate(String apiId, String rawKey) {
        return post("/internal/validate/" + apiId).header("X-Internal-Secret", INTERNAL).header("X-API-Key", rawKey);
    }

    // ---- tests ----

    @Test
    void rolesInvitationsAndTenantIsolation() throws Exception {
        String owner = register("owner@example.test");
        String member = register("member@example.test");
        String outsider = register("outsider@example.test");
        String org = createOrgAndApi(owner, "Acme");

        mvc.perform(get("/organizations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/organizations/" + org + "/apis").header("Authorization", outsider)).andExpect(status().isNotFound());

        // invitation is bound to the invited email
        String invite = send(post("/organizations/" + org + "/invitations"), owner,
                "{\"email\":\"member@example.test\",\"role\":\"MEMBER\"}", 200);
        String token = field(invite, "token");
        send(post("/invitations/accept"), outsider, "{\"token\":\"" + token + "\"}", 403);
        send(post("/invitations/accept"), member, "{\"token\":\"" + token + "\"}", 200);
        send(post("/invitations/accept"), member, "{\"token\":\"" + token + "\"}", 410);   // single use

        // MEMBER is read-only
        send(get("/organizations/" + org + "/apis"), member, null, 200);
        send(post("/organizations/" + org + "/apis"), member,
                "{\"name\":\"X\",\"upstreamUrl\":\"http://demo-api:5678\",\"requestsPerMinute\":5}", 403);
        send(post("/organizations/" + org + "/invitations"), member, "{\"email\":\"x@example.test\",\"role\":\"MEMBER\"}", 403);

        // only the owner changes roles; the owner cannot be demoted
        String memberId = null;
        String members = send(get("/organizations/" + org + "/members"), owner, null, 200);
        for (var node : json.readTree(members)) if (node.get("email").asText().equals("member@example.test")) memberId = node.get("userId").asText();
        send(patch("/organizations/" + org + "/members/" + memberId), member, "{\"role\":\"ADMIN\"}", 403);
        send(patch("/organizations/" + org + "/members/" + memberId), owner, "{\"role\":\"ADMIN\"}", 200);
        send(post("/organizations/" + org + "/apis"), member,
                "{\"name\":\"X\",\"upstreamUrl\":\"http://demo-api:5678\",\"requestsPerMinute\":5}", 200);   // now an ADMIN

        // SSRF: registering an internal address is refused
        send(post("/organizations/" + org + "/apis"), owner,
                "{\"name\":\"Evil\",\"upstreamUrl\":\"http://169.254.169.254\",\"requestsPerMinute\":5}", 400);
    }

    @Test
    void keyLifecycleRotationGraceRevocationAndCacheEviction() throws Exception {
        String owner = register("keys-owner@example.test");
        String other = register("keys-other@example.test");
        String org = createOrgAndApi(owner, "Keys Inc");
        String api = createApi(owner, org);

        mvc.perform(post("/apis/" + api + "/keys").header("Authorization", other)).andExpect(status().isNotFound());

        String created = send(post("/apis/" + api + "/keys"), owner,
                "{\"name\":\"mobile\",\"requestsPerMinute\":3,\"dailyQuota\":10,\"allowedMethods\":\"GET\",\"pathPrefix\":\"/v1\"}", 200);
        String keyId = field(created, "id");
        String rawKey = field(created, "key");

        mvc.perform(validate(api, rawKey)).andExpect(status().isOk())
                .andExpect(jsonPath("$.requestsPerMinute").value(3))
                .andExpect(jsonPath("$.dailyQuota").value(10))
                .andExpect(jsonPath("$.allowedMethods").value("GET"))
                .andExpect(jsonPath("$.pathPrefix").value("/v1"));
        mvc.perform(post("/internal/validate/" + api).header("X-Internal-Secret", "wrong").header("X-API-Key", rawKey))
                .andExpect(status().isForbidden());

        // rotation with a grace period: both secrets work, and the cached copy of the old one is evicted
        stringRedis.opsForValue().set(KeyCache.cacheKey(java.util.UUID.fromString(api), Hashing.sha256(rawKey)), "stale");
        String rotated = send(post("/apis/" + api + "/keys/" + keyId + "/rotate"), owner, "{\"graceMinutes\":5}", 200);
        String newKey = field(rotated, "key");
        assertThat(stringRedis.hasKey(KeyCache.cacheKey(java.util.UUID.fromString(api), Hashing.sha256(rawKey)))).isFalse();
        mvc.perform(validate(api, newKey)).andExpect(status().isOk());
        mvc.perform(validate(api, rawKey)).andExpect(status().isOk())
                .andExpect(jsonPath("$.cacheTtlSeconds").value(lessThanOrEqualTo(300)));

        // immediate rotation: the previous secret stops working at once
        String rotatedAgain = send(post("/apis/" + api + "/keys/" + keyId + "/rotate"), owner, "{\"graceMinutes\":0}", 200);
        String newestKey = field(rotatedAgain, "key");
        mvc.perform(validate(api, newKey)).andExpect(status().isUnauthorized());
        mvc.perform(validate(api, newestKey)).andExpect(status().isOk());

        // editing limits updates what the gateway sees
        send(patch("/apis/" + api + "/keys/" + keyId), owner, "{\"requestsPerMinute\":50}", 200);
        mvc.perform(validate(api, newestKey)).andExpect(jsonPath("$.requestsPerMinute").value(50));

        // revocation
        send(delete("/apis/" + api + "/keys/" + keyId), owner, null, 200);
        mvc.perform(validate(api, newestKey)).andExpect(status().isUnauthorized());
    }

    @Test
    void streamWorkerProducesCorrectAnalytics() throws Exception {
        String owner = register("analytics-owner@example.test");
        String org = createOrgAndApi(owner, "Analytics Inc");
        String api = createApi(owner, org);
        String keyId = field(send(post("/apis/" + api + "/keys"), owner, null, 200), "id");

        String[][] events = {
                {"PROXIED", "200", "10"}, {"PROXIED", "200", "20"}, {"PROXIED", "201", "30"}, {"PROXIED", "500", "40"},
                {"PROXIED", "404", "5"}, {"RATE_LIMITED", "429", "1"}, {"RATE_LIMITED", "429", "1"},
                {"QUOTA_EXCEEDED", "429", "1"}, {"SCOPE_DENIED", "403", "1"}};
        for (String[] e : events) {
            stringRedis.opsForStream().add(StreamRecords.newRecord().in("usage:events").ofMap(Map.of(
                    "org", org, "api", api, "key", keyId, "outcome", e[0], "status", e[1], "latency", e[2],
                    "ts", Long.toString(System.currentTimeMillis()))));
        }

        Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            usageConsumer.poll();
            mvc.perform(get("/organizations/" + org + "/analytics/summary").header("Authorization", owner))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.requests").value(9))
                    .andExpect(jsonPath("$.success").value(3))
                    .andExpect(jsonPath("$.clientErrors").value(1))
                    .andExpect(jsonPath("$.serverErrors").value(1))
                    .andExpect(jsonPath("$.rateLimited").value(2))
                    .andExpect(jsonPath("$.quotaExceeded").value(1))
                    .andExpect(jsonPath("$.scopeDenied").value(1))
                    .andExpect(jsonPath("$.maxLatencyMs").value(40));
        });

        // polling again must not double count (events were acknowledged)
        usageConsumer.poll();
        mvc.perform(get("/organizations/" + org + "/analytics/summary").header("Authorization", owner))
                .andExpect(jsonPath("$.requests").value(9));
        mvc.perform(get("/organizations/" + org + "/analytics/timeseries").header("Authorization", owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].requests").value(9));
        mvc.perform(get("/organizations/" + org + "/analytics/by-api").header("Authorization", owner))
                .andExpect(jsonPath("$[0].name").value("Echo"));

        // another tenant cannot read this organization's analytics
        String outsider = register("analytics-outsider@example.test");
        mvc.perform(get("/organizations/" + org + "/analytics/summary").header("Authorization", outsider)).andExpect(status().isNotFound());
    }
}
