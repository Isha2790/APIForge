package dev.apiforge.management;

import java.util.Arrays;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Tenant isolation lives here. Non-members always get 404, so another tenant's ids reveal nothing. */
@Service
public class Access {
    private final Repositories.Memberships memberships;
    private final Repositories.Apis apis;

    public Access(Repositories.Memberships memberships, Repositories.Apis apis) {
        this.memberships = memberships;
        this.apis = apis;
    }

    public static UUID userId(Authentication auth) {
        return UUID.fromString(auth.getName());
    }

    /** Any membership is enough. */
    public Role role(UUID userId, UUID orgId) {
        return memberships.findByUserIdAndOrganizationId(userId, orgId)
                .map(m -> Role.valueOf(m.role))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    public Role require(UUID userId, UUID orgId, Role... allowed) {
        Role role = role(userId, orgId);
        if (!Arrays.asList(allowed).contains(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Requires role: " + Arrays.toString(allowed));
        }
        return role;
    }

    /** Loads an API the user may access; with no roles given any member may. */
    public Models.ManagedApi api(UUID userId, UUID apiId, Role... allowed) {
        Models.ManagedApi api = apis.findById(apiId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (allowed.length == 0) {
            role(userId, api.organizationId);
        } else {
            require(userId, api.organizationId, allowed);
        }
        return api;
    }
}
