package dev.apiforge.management;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/organizations/{orgId}/apis")
public class ApiRegistryController {
    public record ApiRequest(@NotBlank @Size(max = 150) String name, @NotBlank String upstreamUrl,
                             @Min(1) @Max(100000) int requestsPerMinute) {}

    public record ApiView(UUID id, UUID organizationId, String name, String upstreamUrl, int requestsPerMinute) {
        static ApiView of(Models.ManagedApi api) {
            return new ApiView(api.id, api.organizationId, api.name, api.upstreamUrl, api.requestsPerMinute);
        }
    }

    private final Repositories.Apis apis;
    private final Access access;
    private final SsrfGuard ssrf;
    private final KeyCache cache;

    public ApiRegistryController(Repositories.Apis apis, Access access, SsrfGuard ssrf, KeyCache cache) {
        this.apis = apis;
        this.access = access;
        this.ssrf = ssrf;
        this.cache = cache;
    }

    @PostMapping
    public ApiView create(Authentication auth, @PathVariable UUID orgId, @Valid @RequestBody ApiRequest body) {
        access.require(Access.userId(auth), orgId, Role.OWNER, Role.ADMIN);
        String upstream = ssrf.validate(body.upstreamUrl());
        return ApiView.of(apis.save(new Models.ManagedApi(orgId, body.name().trim(), upstream, body.requestsPerMinute())));
    }

    @GetMapping
    public List<ApiView> list(Authentication auth, @PathVariable UUID orgId) {
        access.role(Access.userId(auth), orgId);
        return apis.findByOrganizationId(orgId).stream().map(ApiView::of).toList();
    }

    @GetMapping("/{apiId}")
    public ApiView get(Authentication auth, @PathVariable UUID orgId, @PathVariable UUID apiId) {
        access.role(Access.userId(auth), orgId);
        return ApiView.of(inOrg(orgId, apiId));
    }

    @PutMapping("/{apiId}")
    public ApiView update(Authentication auth, @PathVariable UUID orgId, @PathVariable UUID apiId,
                          @Valid @RequestBody ApiRequest body) {
        access.require(Access.userId(auth), orgId, Role.OWNER, Role.ADMIN);
        Models.ManagedApi api = inOrg(orgId, apiId);
        api.name = body.name().trim();
        api.upstreamUrl = ssrf.validate(body.upstreamUrl());
        api.requestsPerMinute = body.requestsPerMinute();
        apis.save(api);
        cache.evictApi(apiId);   // gateways must not keep serving the old upstream / limit
        return ApiView.of(api);
    }

    @DeleteMapping("/{apiId}")
    public void delete(Authentication auth, @PathVariable UUID orgId, @PathVariable UUID apiId) {
        access.require(Access.userId(auth), orgId, Role.OWNER, Role.ADMIN);
        Models.ManagedApi api = inOrg(orgId, apiId);
        List<String> hashes = cache.hashesOf(apiId);   // collect before the cascade removes the keys
        apis.delete(api);
        cache.evictAll(apiId, hashes);
    }

    private Models.ManagedApi inOrg(UUID orgId, UUID apiId) {
        return apis.findById(apiId)
                .filter(a -> a.organizationId.equals(orgId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
