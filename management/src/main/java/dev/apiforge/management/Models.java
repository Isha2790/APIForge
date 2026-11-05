package dev.apiforge.management;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** JPA entities. Explicit entity names keep JPQL readable ("ApiKey" rather than "Models$ApiKey"). */
public final class Models {
    private Models() {}

    @Entity(name = "User")
    @Table(name = "users")
    public static class User {
        @Id public UUID id;
        public String email;
        @Column(name = "password_hash") public String passwordHash;

        protected User() {}

        public User(String email, String passwordHash) {
            this.id = UUID.randomUUID();
            this.email = email;
            this.passwordHash = passwordHash;
        }
    }

    @Entity(name = "Organization")
    @Table(name = "organizations")
    public static class Organization {
        @Id public UUID id;
        public String name;
        @Column(name = "owner_id") public UUID ownerId;

        protected Organization() {}

        public Organization(String name, UUID ownerId) {
            this.id = UUID.randomUUID();
            this.name = name;
            this.ownerId = ownerId;
        }
    }

    public static class MembershipId implements Serializable {
        public UUID userId;
        public UUID organizationId;

        public MembershipId() {}

        public MembershipId(UUID userId, UUID organizationId) {
            this.userId = userId;
            this.organizationId = organizationId;
        }

        @Override public boolean equals(Object o) {
            return o instanceof MembershipId m && Objects.equals(userId, m.userId) && Objects.equals(organizationId, m.organizationId);
        }

        @Override public int hashCode() {
            return Objects.hash(userId, organizationId);
        }
    }

    @Entity(name = "Membership")
    @Table(name = "memberships")
    @IdClass(MembershipId.class)
    public static class Membership {
        @Id @Column(name = "user_id") public UUID userId;
        @Id @Column(name = "organization_id") public UUID organizationId;
        public String role;

        protected Membership() {}

        public Membership(UUID userId, UUID organizationId, Role role) {
            this.userId = userId;
            this.organizationId = organizationId;
            this.role = role.name();
        }
    }

    @Entity(name = "Invitation")
    @Table(name = "invitations")
    public static class Invitation {
        @Id public UUID id;
        @Column(name = "organization_id") public UUID organizationId;
        public String email;
        public String role;
        @Column(name = "token_hash") public String tokenHash;
        @Column(name = "invited_by") public UUID invitedBy;
        @Column(name = "expires_at") public Instant expiresAt;
        @Column(name = "accepted_at") public Instant acceptedAt;

        protected Invitation() {}

        public Invitation(UUID organizationId, String email, Role role, String tokenHash, UUID invitedBy, Instant expiresAt) {
            this.id = UUID.randomUUID();
            this.organizationId = organizationId;
            this.email = email;
            this.role = role.name();
            this.tokenHash = tokenHash;
            this.invitedBy = invitedBy;
            this.expiresAt = expiresAt;
        }
    }

    @Entity(name = "PasswordResetToken")
    @Table(name = "password_reset_tokens")
    public static class PasswordResetToken {
        @Id public UUID id;
        @Column(name = "user_id") public UUID userId;
        @Column(name = "token_hash") public String tokenHash;
        @Column(name = "expires_at") public Instant expiresAt;
        @Column(name = "used_at") public Instant usedAt;

        protected PasswordResetToken() {}

        public PasswordResetToken(UUID userId, String tokenHash, Instant expiresAt) {
            this.id = UUID.randomUUID();
            this.userId = userId;
            this.tokenHash = tokenHash;
            this.expiresAt = expiresAt;
        }
    }

    @Entity(name = "ManagedApi")
    @Table(name = "managed_apis")
    public static class ManagedApi {
        @Id public UUID id;
        @Column(name = "organization_id") public UUID organizationId;
        public String name;
        @Column(name = "upstream_url") public String upstreamUrl;
        @Column(name = "requests_per_minute") public int requestsPerMinute;

        protected ManagedApi() {}

        public ManagedApi(UUID organizationId, String name, String upstreamUrl, int requestsPerMinute) {
            this.id = UUID.randomUUID();
            this.organizationId = organizationId;
            this.name = name;
            this.upstreamUrl = upstreamUrl;
            this.requestsPerMinute = requestsPerMinute;
        }
    }

    @Entity(name = "ApiKey")
    @Table(name = "api_keys")
    public static class ApiKey {
        @Id public UUID id;
        @Column(name = "api_id") public UUID apiId;
        public String name;
        public String prefix;
        @Column(name = "key_hash") public String keyHash;
        public boolean revoked;
        /** null = use the API's default limit. */
        @Column(name = "requests_per_minute") public Integer requestsPerMinute;
        @Column(name = "daily_quota") public long dailyQuota;
        @Column(name = "monthly_quota") public long monthlyQuota;
        @Column(name = "allowed_methods") public String allowedMethods;
        @Column(name = "path_prefix") public String pathPrefix;
        @Column(name = "expires_at") public Instant expiresAt;
        @Column(name = "previous_key_hash") public String previousKeyHash;
        @Column(name = "previous_expires_at") public Instant previousExpiresAt;

        protected ApiKey() {}

        public ApiKey(UUID apiId, String name, String prefix, String keyHash) {
            this.id = UUID.randomUUID();
            this.apiId = apiId;
            this.name = name;
            this.prefix = prefix;
            this.keyHash = keyHash;
            this.allowedMethods = "GET,POST,PUT,PATCH,DELETE,HEAD,OPTIONS";
            this.pathPrefix = "/";
        }
    }
}
