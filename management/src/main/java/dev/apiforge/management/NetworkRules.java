package dev.apiforge.management;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** Addresses an upstream must never resolve to (SSRF protection). Mirrored in the gateway. */
public final class NetworkRules {
    private NetworkRules() {}

    public static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;   // includes 169.254.169.254 (cloud metadata) and RFC1918
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0                                   // 0.0.0.0/8
                    || (first == 100 && (second & 0xc0) == 64)  // 100.64.0.0/10 carrier-grade NAT
                    || (first == 198 && (second & 0xfe) == 18); // 198.18.0.0/15 benchmarking
        }
        return a instanceof Inet6Address && (b[0] & 0xfe) == 0xfc; // fc00::/7 unique local
    }
}
