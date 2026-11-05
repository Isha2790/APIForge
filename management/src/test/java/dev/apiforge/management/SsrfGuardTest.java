package dev.apiforge.management;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** IP literals only, so no DNS lookups happen. */
class SsrfGuardTest {
    private final SsrfGuard guard = new SsrfGuard("demo-api");

    @Test
    void rejectsInternalTargets() {
        for (String url : new String[]{"http://127.0.0.1:8080", "http://10.0.0.5", "http://192.168.1.10/x",
                "http://169.254.169.254/latest/meta-data", "http://100.64.0.1", "http://[::1]/", "http://0.0.0.0"}) {
            assertThatThrownBy(() -> guard.validate(url)).as(url).isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void rejectsUnsafeShapes() {
        for (String url : new String[]{"ftp://8.8.8.8", "file:///etc/passwd", "http://user:pw@8.8.8.8", "http://8.8.8.8/?q=1",
                "http://8.8.8.8/#frag", "not a url", "//8.8.8.8", "http://8.8.8.8:70000"}) {
            assertThatThrownBy(() -> guard.validate(url)).as(url).isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test
    void acceptsPublicAndExplicitlyAllowedHosts() {
        assertThat(guard.validate("http://8.8.8.8/")).isEqualTo("http://8.8.8.8");
        assertThat(guard.validate("https://1.1.1.1:8443/v1/")).isEqualTo("https://1.1.1.1:8443/v1");
        assertThat(guard.validate("http://demo-api:5678")).isEqualTo("http://demo-api:5678");   // allow-listed, no DNS needed
    }
}
