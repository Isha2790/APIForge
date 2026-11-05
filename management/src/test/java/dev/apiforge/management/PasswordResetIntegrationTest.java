package dev.apiforge.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Password reset and invitation email, on real Postgres and Redis. The mail server is replaced by a mock so the
 * tests can read the emailed link. Each test uses its own addresses (Redis counters persist across tests).
 * Skipped automatically without Docker.
 */
@SpringBootTest(properties = {"app.usage.poll-ms=3600000"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PasswordResetIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection(name = "redis")
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final String PASSWORD = "StrongPassword123!";
    private static final String NEW_PASSWORD = "BrandNewPassword456!";
    private static final String RESET_SUBJECT = "Reset your APIForge password";
    private static final Pattern LINK_TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @MockBean Mailer mailer;

    // ---- helpers ----

    private int postStatus(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }

    private String postBody(String path, String body, String auth) throws Exception {
        var request = post(path).contentType(APPLICATION_JSON).content(body);
        if (auth != null) request = request.header("Authorization", auth);
        return mvc.perform(request).andReturn().getResponse().getContentAsString();
    }

    private void register(String email) throws Exception {
        assertThat(postStatus("/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}")).isEqualTo(200);
    }

    private String registerForBearer(String email) throws Exception {
        String body = postBody("/auth/register", "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}", null);
        return "Bearer " + json.readTree(body).get("token").asText();
    }

    private int login(String email, String password) throws Exception {
        return postStatus("/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private int forgot(String email) throws Exception {
        return postStatus("/auth/forgot-password", "{\"email\":\"" + email + "\"}");
    }

    private int reset(String token, String password) throws Exception {
        return postStatus("/auth/reset-password", "{\"token\":\"" + token + "\",\"newPassword\":\"" + password + "\"}");
    }

    /** Tokens found in every reset email sent to this address so far, oldest first. */
    private List<String> emailedTokens(String email) {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(mailer, atLeast(0)).send(eq(email), eq(RESET_SUBJECT), body.capture());
        List<String> tokens = new ArrayList<>();
        for (String text : body.getAllValues()) {
            Matcher matcher = LINK_TOKEN.matcher(text);
            assertThat(matcher.find()).as("email contains a reset link").isTrue();
            tokens.add(matcher.group(1));
        }
        return tokens;
    }

    // ---- tests ----

    @Test
    void unknownAddressGetsTheSameAnswerAndNoEmail() throws Exception {
        String email = "nobody-here@example.test";
        String body = postBody("/auth/forgot-password", "{\"email\":\"" + email + "\"}", null);
        assertThat(forgot(email)).isEqualTo(202);
        assertThat(json.readTree(body).get("message").asText()).isEqualTo(PasswordResetController.ACCEPTED_TEXT);
        verify(mailer, never()).send(eq(email), anyString(), anyString());
    }

    @Test
    void fullResetFlowChangesThePasswordAndTheLinkWorksOnce() throws Exception {
        String email = "reset-flow@example.test";
        register(email);
        assertThat(forgot(email)).isEqualTo(202);

        List<String> tokens = emailedTokens(email);
        assertThat(tokens).hasSize(1);
        assertThat(tokens.get(0)).startsWith("rst_");

        assertThat(reset(tokens.get(0), NEW_PASSWORD)).isEqualTo(200);
        assertThat(login(email, NEW_PASSWORD)).isEqualTo(200);
        assertThat(login(email, PASSWORD)).isEqualTo(401);
        assertThat(reset(tokens.get(0), "AnotherPassword789!")).isEqualTo(400);        // single use
        verify(mailer, times(1)).send(eq(email), eq("Your APIForge password was changed"), anyString());
    }

    @Test
    void aTooShortPasswordDoesNotSpendTheLink() throws Exception {
        String email = "reset-weak@example.test";
        register(email);
        forgot(email);
        String token = emailedTokens(email).get(0);

        assertThat(reset(token, "short")).isEqualTo(400);
        assertThat(reset(token, NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    void anExpiredLinkIsRejected() throws Exception {
        String email = "reset-expired@example.test";
        register(email);
        forgot(email);
        String token = emailedTokens(email).get(0);

        jdbc.update("update password_reset_tokens set expires_at = now() - interval '1 minute' where token_hash = ?",
                Hashing.sha256(token));
        assertThat(reset(token, NEW_PASSWORD)).isEqualTo(400);
        assertThat(login(email, PASSWORD)).isEqualTo(200);                              // password unchanged
    }

    @Test
    void aNewRequestVoidsTheOlderLink() throws Exception {
        String email = "reset-newest@example.test";
        register(email);
        forgot(email);
        forgot(email);
        List<String> tokens = emailedTokens(email);
        assertThat(tokens).hasSize(2);

        assertThat(reset(tokens.get(0), NEW_PASSWORD)).isEqualTo(400);
        assertThat(reset(tokens.get(1), NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    void resetRequestsAreLimitedPerAddress() throws Exception {
        String email = "reset-limit@example.test";
        register(email);
        for (int i = 0; i < 5; i++) assertThat(forgot(email)).isEqualTo(202);           // the answer never reveals the limit
        verify(mailer, times(ResetRequestLimiter.MAX_PER_HOUR)).send(eq(email), eq(RESET_SUBJECT), anyString());
    }

    @Test
    void aSuccessfulResetClearsTheLoginLockout() throws Exception {
        String email = "reset-lockout@example.test";
        register(email);
        for (int i = 0; i < LoginThrottle.MAX_FAILURES; i++) assertThat(login(email, "wrong-password-1")).isEqualTo(401);
        assertThat(login(email, PASSWORD)).isEqualTo(429);                              // locked out, even with the right password

        forgot(email);
        assertThat(reset(emailedTokens(email).get(0), NEW_PASSWORD)).isEqualTo(200);
        assertThat(login(email, NEW_PASSWORD)).isEqualTo(200);
    }

    @Test
    void invitationsAreEmailedToTheInvitee() throws Exception {
        String owner = registerForBearer("mail-owner@example.test");
        String invitee = "mail-invitee@example.test";
        String orgBody = postBody("/organizations", "{\"name\":\"Acme Mail\"}", owner);
        String orgId = json.readTree(orgBody).get("id").asText();

        String created = postBody("/organizations/" + orgId + "/invitations",
                "{\"email\":\"" + invitee + "\",\"role\":\"MEMBER\"}", owner);
        String token = json.readTree(created).get("token").asText();

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mailer).send(eq(invitee), eq("You are invited to Acme Mail on APIForge"), text.capture());
        assertThat(text.getValue()).contains(token).contains("Acme Mail");
    }
}
