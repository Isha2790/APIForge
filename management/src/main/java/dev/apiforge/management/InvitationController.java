package dev.apiforge.management;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/invitations")
public class InvitationController {
    public record AcceptRequest(@NotBlank String token) {}

    public record Accepted(UUID organizationId, String role) {}

    private final Repositories.Invitations invitations;
    private final Repositories.Memberships memberships;
    private final Repositories.Users users;

    public InvitationController(Repositories.Invitations invitations, Repositories.Memberships memberships, Repositories.Users users) {
        this.invitations = invitations;
        this.memberships = memberships;
        this.users = users;
    }

    @PostMapping("/accept")
    @Transactional
    public Accepted accept(Authentication auth, @Valid @RequestBody AcceptRequest body) {
        UUID userId = Access.userId(auth);
        Models.Invitation invitation = invitations.findByTokenHash(Hashing.sha256(body.token().trim()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Invalid invitation"));
        if (invitation.acceptedAt != null || invitation.expiresAt.isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "Invitation expired or already used");
        }
        Models.User user = users.findById(userId).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        if (!user.email.equalsIgnoreCase(invitation.email)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This invitation was issued to a different email");
        }
        if (memberships.findByUserIdAndOrganizationId(userId, invitation.organizationId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already a member");
        }
        memberships.save(new Models.Membership(userId, invitation.organizationId, Role.valueOf(invitation.role)));
        invitation.acceptedAt = Instant.now();
        invitations.save(invitation);
        return new Accepted(invitation.organizationId, invitation.role);
    }
}
