package com.agentic.shortener.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Who is calling: the authenticated principal, or the client IP for anonymous traffic.
 * Used for rate limiting and link ownership.
 */
public final class ClientIdentity {

    private ClientIdentity() {
    }

    public static String resolve(HttpServletRequest request) {
        var principal = request.getUserPrincipal();
        if (principal != null) {
            return principal.getName();
        }
        // remoteAddr is the real client only when server.forward-headers-strategy matches the proxy setup.
        return "ip:" + request.getRemoteAddr();
    }
}
