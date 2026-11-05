package dev.apiforge.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;

class NetworkRulesTest {
    private static boolean internal(String ip) throws Exception {
        return NetworkRules.isInternal(InetAddress.getByName(ip));   // IP literals: no DNS lookup happens
    }

    @Test
    void blocksInternalRanges() throws Exception {
        for (String ip : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "169.254.169.254",
                "100.64.0.1", "0.0.0.0", "198.18.0.1", "::1", "fd00::1", "fe80::1"}) {
            assertThat(internal(ip)).as(ip).isTrue();
        }
    }

    @Test
    void allowsPublicAddresses() throws Exception {
        for (String ip : new String[]{"8.8.8.8", "1.1.1.1", "93.184.216.34", "2606:4700:4700::1111"}) {
            assertThat(internal(ip)).as(ip).isFalse();
        }
    }
}
