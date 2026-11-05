package dev.apiforge.management;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
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
@RequestMapping("/organizations")
public class OrganizationController {
    public record NewOrg(@NotBlank @Size(max = 150) String name) {}

    public record OrgView(UUID id, String name, String role) {}

    public record MemberView(UUID userId, String email, String role) {}

    public record RoleChange(@NotBlank String role) {}

    public record NewInvitation(@Email @NotBlank String email, @NotBlank String role) {}

    public record InvitationView(UUID id, String email, String role, Instant expiresAt) {}

    public record CreatedInvitation(UUID id, String email, String role, Instant expiresAt, String token, String note) {}

    private static final Duration INVITATION_TTL = Duration.ofDays(7);

    private final Repositories.Organizations orgs;
    private final Repositories.Memberships memberships;
    private final Repositories.Invitations invitations;
    private final Access access;
    private final JdbcTemplate jdbc;
    private final Mailer mailer;
    private final String publicUrl;

    public OrganizationController(Repositories.Organizations orgs, Repositories.Memberships memberships,
                                  Repositories.Invitations invitations, Access access, JdbcTemplate jdbc,
                                  Mailer mailer, @Value("${app.public-url}") String publicUrl) {
        this.orgs = orgs;
        this.memberships = memberships;
        this.invitations = invitations;
        this.access = access;
        this.jdbc = jdbc;
        this.mailer = mailer;
        this.publicUrl = publicUrl;
    }

    @PostMapping
    @Transactional
    public OrgView create(Authentication auth, @Valid @RequestBody NewOrg body) {
        UUID userId = Access.userId(auth);
        Models.Organization org = orgs.save(new Models.Organization(body.name().trim(), userId));
        memberships.save(new Models.Membership(userId, org.id, Role.OWNER));
        return new OrgView(org.id, org.name, Role.OWNER.name());
    }

    @GetMapping
    public List<OrgView> mine(Authentication auth) {
        return jdbc.query(
                "SELECT o.id, o.name, m.role FROM memberships m JOIN organizations o ON o.id = m.organization_id "
                        + "WHERE m.user_id = ? ORDER BY o.created_at",
                (rs, i) -> new OrgView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("role")),
                Access.userId(auth));
    }

    // ---- members ----

    @GetMapping("/{orgId}/members")
    public List<MemberView> members(Authentication auth, @PathVariable UUID orgId) {
        access.role(Access.userId(auth), orgId);
        return jdbc.query(
                "SELECT u.id, u.email, m.role FROM memberships m JOIN users u ON u.id = m.user_id "
                        + "WHERE m.organization_id = ? ORDER BY u.email",
                (rs, i) -> new MemberView(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("role")),
                orgId);
    }

    @PatchMapping("/{orgId}/members/{userId}")
    public MemberView changeRole(Authentication auth, @PathVariable UUID orgId, @PathVariable UUID userId,
                                 @Valid @RequestBody RoleChange body) {
        access.require(Access.userId(auth), orgId, Role.OWNER);
        Role newRole = invitableRole(body.role());
        Models.Membership target = memberships.findByUserIdAndOrganizationId(userId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (Role.OWNER.name().equals(target.role)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The owner's role cannot be changed");
        }
        target.role = newRole.name();
        memberships.save(target);
        return jdbc.queryForObject("SELECT u.id, u.email, m.role FROM memberships m JOIN users u ON u.id = m.user_id "
                        + "WHERE m.organization_id = ? AND m.user_id = ?",
                (rs, i) -> new MemberView(rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("role")),
                orgId, userId);
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    public void removeMember(Authentication auth, @PathVariable UUID orgId, @PathVariable UUID userId) {
        access.require(Access.userId(auth), orgId, Role.OWNER);
        Models.Membership target = memberships.findByUserIdAndOrganizationId(userId, orgId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (Role.OWNER.name().equals(target.role)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The owner cannot be removed");
        }
        memberships.delete(target);
    }

    // ---- invitations (the token is emailed to the invitee and also shown once to the inviter) ----

    @PostMapping("/{orgId}/invitations")
    public CreatedInvitation invite(Authentication auth, @PathVariable UUID orgId, @Valid @RequestBody NewInvitation body) {
        Role inviter = access.require(Access.userId(auth), orgId, Role.OWNER, Role.ADMIN);
        Role role = invitableRole(body.role());
        if (inviter == Role.ADMIN && role != Role.MEMBER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admins can only invite members");
        }
        String token = Hashing.randomToken("inv_");
        Models.Invitation invitation = invitations.save(new Models.Invitation(
                orgId, body.email().trim().toLowerCase(Locale.ROOT), role, Hashing.sha256(token),
                Access.userId(auth), Instant.now().plus(INVITATION_TTL)));
        String orgName = orgs.findById(orgId).map(o -> o.name).orElse("an organization");
        mailer.send(invitation.email, "You are invited to " + orgName + " on APIForge",
                "You have been invited to join \"" + orgName + "\" on APIForge as " + invitation.role + ".\n\n"
                        + "1. Open " + publicUrl + " and register or sign in with this email address (" + invitation.email + ").\n"
                        + "2. Paste this token into \"Have an invitation token?\" and press \"Join organization\":\n\n"
                        + token + "\n\n"
                        + "The token works once and expires in 7 days.\n");
        return new CreatedInvitation(invitation.id, invitation.email, invitation.role, invitation.expiresAt, token,
                "The invitation was emailed. The token is also shown here once, in case you want to share it yourself.");
    }

    @GetMapping("/{orgId}/invitations")
    public List<InvitationView> pending(Authentication auth, @PathVariable UUID orgId) {
        access.require(Access.userId(auth), orgId, Role.OWNER, Role.ADMIN);
        return invitations.findByOrganizationIdAndAcceptedAtIsNull(orgId).stream()
                .map(i -> new InvitationView(i.id, i.email, i.role, i.expiresAt)).toList();
    }

    @DeleteMapping("/{orgId}/invitations/{invitationId}")
    public void revokeInvitation(Authentication auth, @PathVariable UUID orgId, @PathVariable UUID invitationId) {
        access.require(Access.userId(auth), orgId, Role.OWNER, Role.ADMIN);
        Models.Invitation invitation = invitations.findById(invitationId)
                .filter(i -> i.organizationId.equals(orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        invitations.delete(invitation);
    }

    private static Role invitableRole(String value) {
        try {
            Role role = Role.valueOf(value.toUpperCase(Locale.ROOT));
            if (role == Role.OWNER) throw new IllegalArgumentException();
            return role;
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be ADMIN or MEMBER");
        }
    }
}
