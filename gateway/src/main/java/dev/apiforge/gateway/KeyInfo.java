package dev.apiforge.gateway;

/** What the management service tells us about a valid API key (also the cached form). */
record KeyInfo(String keyId, String orgId, String apiId, String upstreamUrl, int requestsPerMinute, long dailyQuota,
               long monthlyQuota, String allowedMethods, String pathPrefix, int cacheTtlSeconds) {

    boolean allowsMethod(String method) {
        if (allowedMethods == null || allowedMethods.isBlank()) return true;
        for (String allowed : allowedMethods.split(",")) {
            if (allowed.trim().equalsIgnoreCase(method)) return true;
        }
        return false;
    }

    /** Prefix match on whole path segments: "/v1" allows "/v1" and "/v1/x" but not "/v10". */
    boolean allowsPath(String path) {
        String prefix = pathPrefix == null || pathPrefix.isBlank() ? "/" : pathPrefix;
        if (prefix.equals("/")) return true;
        String base = prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return path.equals(base) || path.startsWith(base + "/");
    }
}
