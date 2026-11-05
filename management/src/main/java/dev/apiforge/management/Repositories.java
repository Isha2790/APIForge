package dev.apiforge.management;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public final class Repositories {
    private Repositories() {}

    public interface Users extends JpaRepository<Models.User, UUID> {
        Optional<Models.User> findByEmail(String email);
    }

    public interface Organizations extends JpaRepository<Models.Organization, UUID> {}

    public interface Memberships extends JpaRepository<Models.Membership, Models.MembershipId> {
        List<Models.Membership> findByUserId(UUID userId);
        Optional<Models.Membership> findByUserIdAndOrganizationId(UUID userId, UUID organizationId);
    }

    public interface Invitations extends JpaRepository<Models.Invitation, UUID> {
        Optional<Models.Invitation> findByTokenHash(String tokenHash);
        List<Models.Invitation> findByOrganizationIdAndAcceptedAtIsNull(UUID organizationId);
    }

    public interface ResetTokens extends JpaRepository<Models.PasswordResetToken, UUID> {
        Optional<Models.PasswordResetToken> findByTokenHash(String tokenHash);

        /** Atomically spends a token: returns 1 if it was unused and unexpired, 0 otherwise (so two clicks cannot both win). */
        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("update PasswordResetToken t set t.usedAt = :now where t.tokenHash = :hash and t.usedAt is null and t.expiresAt > :now")
        int claim(@Param("hash") String hash, @Param("now") Instant now);

        /** Voids every unused link of a user (a newer request, or a completed reset, replaces older emails). */
        @Modifying(clearAutomatically = true, flushAutomatically = true)
        @Query("update PasswordResetToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
        int invalidateAll(@Param("userId") UUID userId, @Param("now") Instant now);
    }

    public interface Apis extends JpaRepository<Models.ManagedApi, UUID> {
        List<Models.ManagedApi> findByOrganizationId(UUID organizationId);
    }

    public interface Keys extends JpaRepository<Models.ApiKey, UUID> {
        List<Models.ApiKey> findByApiId(UUID apiId);

        Optional<Models.ApiKey> findByIdAndApiId(UUID id, UUID apiId);

        /** A key is valid if it is not revoked and the presented hash is its current hash, or its previous hash still inside the rotation grace period. */
        @Query("select k from ApiKey k where k.apiId = :apiId and k.revoked = false "
                + "and (k.keyHash = :hash or (k.previousKeyHash = :hash and k.previousExpiresAt > :now))")
        Optional<Models.ApiKey> findValid(@Param("apiId") UUID apiId, @Param("hash") String hash, @Param("now") Instant now);
    }
}
