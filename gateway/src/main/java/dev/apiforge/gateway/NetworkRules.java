package dev.apiforge.gateway;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;

/** Addresses an upstream must never resolve to. Mirrors the management service's registration-time check. */
final class NetworkRules {
    private NetworkRules() {}

    static boolean isInternal(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress()
                || a.isSiteLocalAddress() || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int first = b[0] & 0xff;
            int second = b[1] & 0xff;
            return first == 0 || (first == 100 && (second & 0xc0) == 64) || (first == 198 && (second & 0xfe) == 18);
        }
        return a instanceof Inet6Address && (b[0] & 0xfe) == 0xfc;
    }
}
