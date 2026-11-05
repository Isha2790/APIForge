package dev.apiforge.gateway;

import java.net.InetAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Request-time SSRF check. Registration already validated the URL, but DNS can change afterwards, so the
 * upstream host is re-resolved (off the event loop, cached 30 s) and refused if it now points at an internal
 * address. This narrows, but cannot fully close, the DNS-rebinding window (the HTTP client resolves again).
 */
@Component
class UpstreamGuard {
    private static final Logger log = LoggerFactory.getLogger(UpstreamGuard.class);
    private static final long OK_FOR_MS = 30_000;

    private final Set<String> allowedPrivateHosts;
    private final Map<String, Long> okUntil = new ConcurrentHashMap<>();

    UpstreamGuard(@Value("${app.allow-private-hosts:}") String allowPrivateHosts) {
        this.allowedPrivateHosts = Arrays.stream(allowPrivateHosts.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    Mono<Boolean> isAllowed(String upstreamUrl) {
        String host;
        try {
            host = URI.create(upstreamUrl).getHost();
        } catch (IllegalArgumentException e) {
            return Mono.just(false);
        }
        if (host == null) return Mono.just(false);
        String normalized = host.toLowerCase(Locale.ROOT);
        if (allowedPrivateHosts.contains(normalized)) return Mono.just(true);
        Long until = okUntil.get(normalized);
        if (until != null && until > System.currentTimeMillis()) return Mono.just(true);

        return Mono.fromCallable(() -> {
                    for (InetAddress address : InetAddress.getAllByName(normalized)) {
                        if (NetworkRules.isInternal(address)) return false;
                    }
                    return true;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doOnNext(ok -> {
                    if (ok) okUntil.put(normalized, System.currentTimeMillis() + OK_FOR_MS);
                    else log.warn("blocked upstream {}: resolves to an internal address", normalized);
                })
                .onErrorReturn(false);
    }
}
