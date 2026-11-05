package dev.apiforge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KeyInfoTest {
    private static KeyInfo key(String methods, String prefix) {
        return new KeyInfo("k", "o", "a", "http://x", 10, 0, 0, methods, prefix, 60);
    }

    @Test
    void methodScope() {
        KeyInfo readOnly = key("GET,HEAD", "/");
        assertThat(readOnly.allowsMethod("GET")).isTrue();
        assertThat(readOnly.allowsMethod("get")).isTrue();
        assertThat(readOnly.allowsMethod("POST")).isFalse();
    }

    @Test
    void pathScopeMatchesWholeSegments() {
        KeyInfo v1 = key("GET", "/v1");
        assertThat(v1.allowsPath("/v1")).isTrue();
        assertThat(v1.allowsPath("/v1/users")).isTrue();
        assertThat(v1.allowsPath("/v10")).isFalse();
        assertThat(v1.allowsPath("/admin")).isFalse();
        assertThat(key("GET", "/").allowsPath("/anything")).isTrue();
    }

    @Test
    void suspiciousPathsAreRejected() {
        assertThat(GatewayRoutes.isSuspicious("/v1/../admin")).isTrue();
        assertThat(GatewayRoutes.isSuspicious("/v1/%2e%2e/admin")).isTrue();
        assertThat(GatewayRoutes.isSuspicious("/v1//admin")).isTrue();
        assertThat(GatewayRoutes.isSuspicious("/v1/users/42")).isFalse();
    }
}
