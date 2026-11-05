package dev.apiforge.management;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
public class AuthController {
    public record Credentials(@Email @NotBlank String email, @NotBlank String password) {}

    public record TokenResponse(String token) {}

    private final Repositories.Users users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final LoginThrottle throttle;

    public AuthController(Repositories.Users users, PasswordEncoder encoder, JwtService jwt, LoginThrottle throttle) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.throttle = throttle;
    }

    @PostMapping("/register")
    public TokenResponse register(@Valid @RequestBody Credentials body) {
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        if (body.password().length() < 10 || body.password().length() > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Password must be 10-72 characters");
        }
        if (users.findByEmail(email).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already registered");
        }
        Models.User user = users.save(new Models.User(email, encoder.encode(body.password())));
        return new TokenResponse(jwt.issue(user.id));
    }

    @PostMapping("/login")
    public TokenResponse login(@Valid @RequestBody Credentials body) {
        String email = body.email().trim().toLowerCase(Locale.ROOT);
        if (throttle.isLocked(email)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many failed attempts, try again later");
        }
        Models.User user = users.findByEmail(email).orElse(null);
        if (user == null || !encoder.matches(body.password(), user.passwordHash)) {
            throttle.recordFailure(email);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        throttle.reset(email);
        return new TokenResponse(jwt.issue(user.id));
    }
}
