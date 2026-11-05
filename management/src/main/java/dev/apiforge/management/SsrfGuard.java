package dev.apiforge.management;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates upstream URLs when an API is registered: http(s) only, no credentials/query/fragment, and the host
 * must not resolve to a private, loopback, link-local or metadata address. Hosts listed in
 * app.ssrf.allowed-private-hosts (e.g. the compose "demo-api") are the only exception.
 * The gateway repeats the address check at request time to narrow the DNS-rebinding window.
 */
@Component
public class SsrfGuard {
    private final Set<String> allowedPrivateHosts;

    public SsrfGuard(@Value("${app.ssrf.allowed-private-hosts:}") String allowedPrivateHosts) {
        this.allowedPrivateHosts = Arrays.stream(allowedPrivateHosts.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /** Returns the normalized upstream URL (no trailing slash). */
    public String validate(String raw) {
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw bad("Invalid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!List.of("http", "https").contains(scheme) || uri.getHost() == null) {
            throw bad("Only absolute http(s) URLs are allowed");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw bad("URL must not contain credentials, a query string or a fragment");
        }
        if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
            throw bad("Invalid port");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!allowedPrivateHosts.contains(host)) {
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (NetworkRules.isInternal(address)) {
                        throw bad("Upstream resolves to a private or internal address");
                    }
                }
            } catch (UnknownHostException e) {
                throw bad("Upstream host does not resolve");
            }
        }
        return uri.toString().replaceAll("/+$", "");
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
