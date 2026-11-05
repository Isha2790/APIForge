package dev.apiforge.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Low-cardinality metrics only: tags are outcome / cache result, never key or API ids. */
@Component
class RequestMetrics {
    private final MeterRegistry registry;

    RequestMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void request(String outcome, long startNanos) {
        String tag = outcome.toLowerCase();
        registry.counter("apiforge.requests", "outcome", tag).increment();
        registry.timer("apiforge.request.duration", "outcome", tag).record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    void cache(String result) {
        registry.counter("apiforge.cache", "result", result).increment();
    }
}
