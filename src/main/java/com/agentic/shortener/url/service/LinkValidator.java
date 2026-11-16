package com.agentic.shortener.url.service;

import com.agentic.shortener.url.error.InvalidLinkException;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Input rules for links. The destination checks are the open-redirect / SSRF guardrail:
 * http(s) only, no embedded credentials, no loopback, private, link-local or reserved targets.
 */
@Component
public class LinkValidator {

    public static final int MAX_URL_LENGTH = 2048;
    public static final long MIN_TTL_SECONDS = 60;
    public static final long MAX_TTL_SECONDS = 365L * 24 * 3600;

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");
    private static final Pattern ALIAS = Pattern.compile("^[A-Za-z0-9_-]{3,32}$");
    // Aliases share the root path with framework endpoints, so these must never be claimable.
    private static final Set<String> RESERVED_ALIASES = Set.of(
            "api", "actuator", "orchestrator", "swagger-ui", "v3", "health", "admin", "static", "index", "metrics");
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost", "localhost.localdomain", "metadata", "metadata.google.internal", "instance-data");
    private static final Pattern DOTTED_QUAD = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    // Decimal/hex/octal forms like 2130706433 or 0x7f.1 are still loopback to most HTTP clients.
    private static final Pattern NUMERIC_HOST = Pattern.compile("^(0x[0-9a-f]+|\\d+)(\\.(0x[0-9a-f]+|\\d+))*$");

    public String normalizeDestination(String raw) {
        if (raw == null || raw.isBlank()) {
            throw invalidUrl("url is required");
        }
        var url = raw.strip();
        if (url.length() > MAX_URL_LENGTH) {
            throw invalidUrl("url exceeds " + MAX_URL_LENGTH + " characters");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalidUrl("url is malformed");
        }
        var scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            throw invalidUrl("only http and https destinations are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw invalidUrl("credentials in the url are not allowed");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw invalidUrl("url must include a valid host");
        }
        assertPublicHost(uri.getHost());
        return url;
    }

    public Optional<String> validateAlias(String alias) {
        if (alias == null || alias.isBlank()) {
            return Optional.empty();
        }
        if (!ALIAS.matcher(alias).matches()) {
            throw new InvalidLinkException("alias", "alias must be 3-32 characters of [A-Za-z0-9_-]");
        }
        if (RESERVED_ALIASES.contains(alias.toLowerCase(Locale.ROOT))) {
            throw new InvalidLinkException("alias", "alias '" + alias + "' is reserved");
        }
        return Optional.of(alias);
    }

    public Optional<Duration> validateTtl(Long ttlSeconds) {
        if (ttlSeconds == null) {
            return Optional.empty();
        }
        if (ttlSeconds < MIN_TTL_SECONDS || ttlSeconds > MAX_TTL_SECONDS) {
            throw new InvalidLinkException("ttlSeconds",
                    "ttlSeconds must be between " + MIN_TTL_SECONDS + " and " + MAX_TTL_SECONDS);
        }
        return Optional.of(Duration.ofSeconds(ttlSeconds));
    }

    private void assertPublicHost(String rawHost) {
        var host = rawHost.toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (BLOCKED_HOSTS.contains(host) || host.endsWith(".localhost")
                || host.endsWith(".internal") || host.endsWith(".local")) {
            throw blockedHost();
        }
        boolean ipLiteral = host.startsWith("[") || DOTTED_QUAD.matcher(host).matches();
        if (!ipLiteral) {
            if (NUMERIC_HOST.matcher(host).matches()) {
                throw blockedHost();
            }
            // Hostnames are not resolved here (no DNS on the write path); see DNS-rebinding note in the docs.
            return;
        }
        if (isNonPublic(parseLiteral(host))) {
            throw blockedHost();
        }
    }

    private static InetAddress parseLiteral(String literal) {
        try {
            // Literal only: getByName performs no DNS lookup for IPv4/IPv6 literals.
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw invalidUrl("url host is not a valid IP address");
        }
    }

    static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        return switch (address) {
            case Inet4Address ignored -> {
                int first = b[0] & 0xff;
                int second = b[1] & 0xff;
                yield first == 0                                      // 0.0.0.0/8
                        || (first == 100 && second >= 64 && second <= 127) // CGNAT 100.64/10
                        || (first == 192 && second == 0 && (b[2] & 0xff) == 0) // 192.0.0.0/24
                        || (first == 198 && (second == 18 || second == 19))    // benchmarking
                        || first >= 240;                                  // reserved + broadcast
            }
            case Inet6Address ignored -> (b[0] & 0xfe) == 0xfc;           // unique local fc00::/7
            default -> true;
        };
    }

    private static InvalidLinkException invalidUrl(String message) {
        return new InvalidLinkException("url", message);
    }

    private static InvalidLinkException blockedHost() {
        return invalidUrl("destination must not target a private, loopback or reserved host");
    }
}
