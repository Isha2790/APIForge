package dev.apiforge.management;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Forgotten-password flow, under /auth (already public in SecurityConfig).
 *
 * Request: always answers 202 with the same text, whether or not the address is registered, so the form cannot be
 * used to find out who has an account. Confirm: the emailed token is single use, expires after 30 minutes, and only
 * its SHA-256 is stored. A successful reset voids all other outstanding links and clears any login lockout.
 */
@RestController
@RequestMapping("/auth")
public class PasswordResetController {
    public record ForgotRequest(@Email @NotBlank String email) {}

    public record ResetRequest(@NotBlank String token, @NotBlank String newPassword) {}

    public record Message(String message) {}

    static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    static final String ACCEPTED_TEXT = "If that email is registered, a reset link is on its way.";

    private final Repositories.Users users;
    private final Repositories.ResetTokens tokens;
    private final PasswordEncoder encoder;
    private final LoginThrottle loginThrottle;
    private final ResetRequestLimiter limiter;
    private final Mailer mailer;
    private final String publicUrl;

    public PasswordResetController(Repositories.Users users, Repositories.ResetTokens tokens, PasswordEncoder encoder,
                                   LoginThrottle loginThrottle, ResetRequestLimiter limiter, Mailer mailer,
                                   @Value("${app.public-url}") String publicUrl) {
        this.users = users;
        this.tokens = tokens;
        this.encoder = encoder;
        this.loginThrottle = loginThrottle;
        this.limiter = limiter;
        this.mailer = mailer;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    @PostMapping("/forgot-password")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    public Message forgot(@Valid @RequestBody ForgotRequest body) {
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        Models.User user = users.findByEmail(email).orElse(null);
        if (user != null && limiter.tryAcquire(email)) {
            tokens.invalidateAll(user.id, Instant.now());          // only the newest link works
            String token = Hashing.randomToken("rst_");
            tokens.save(new Models.PasswordResetToken(user.id, Hashing.sha256(token), Instant.now().plus(TOKEN_TTL)));
            mailer.send(user.email, "Reset your APIForge password",
                    "Someone asked to reset the password for your APIForge account.\n\n"
                            + "Choose a new password here (the link works once and expires in 30 minutes):\n"
                            + publicUrl + "/reset-password?token=" + token + "\n\n"
                            + "If you did not ask for this, ignore this email. Your password stays the same.\n");
        }
        return new Message(ACCEPTED_TEXT);
    }

    @PostMapping("/reset-password")
    @Transactional
    public Message reset(@Valid @RequestBody ResetRequest body) {
        // same rule as registration; checked first so a typo does not spend the link
        if (body.newPassword().length() < 10 || body.newPassword().length() > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be 10-72 characters");
        }
        String hash = Hashing.sha256(body.token().trim());
        Instant now = Instant.now();
        if (tokens.claim(hash, now) != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This reset link is invalid or has expired");
        }
        Models.PasswordResetToken token = tokens.findByTokenHash(hash)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "This reset link is invalid or has expired"));
        Models.User user = users.findById(token.userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "This reset link is invalid or has expired"));
        user.passwordHash = encoder.encode(body.newPassword());
        users.save(user);
        tokens.invalidateAll(user.id, now);                          // any other outstanding links
        loginThrottle.reset(user.email);                             // a locked-out user can sign in right away
        mailer.send(user.email, "Your APIForge password was changed",
                "The password for your APIForge account was just changed.\n\n"
                        + "If this was not you, reset it again from the sign-in page and tell your organization owner.\n");
        return new Message("Password updated. You can sign in now.");
    }
}
