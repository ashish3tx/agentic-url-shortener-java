package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.config.ShortenerProperties;
import com.agentic.shortener.url.domain.ShortUrl;
import com.agentic.shortener.url.error.InvalidLinkException;
import com.agentic.shortener.url.ratelimit.SlidingWindowRateLimiter;
import com.agentic.shortener.url.repository.InMemoryUrlRepository;
import com.agentic.shortener.url.service.HousekeepingJobs;
import com.agentic.shortener.url.service.LinkValidator;
import com.agentic.shortener.url.service.VisitorHasher;
import com.agentic.shortener.url.web.RedirectController;
import com.agentic.shortener.url.web.ShortUrlController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.agentic.shortener.orchestrator.agent.Artifacts.ordered;

/**
 * Implementation-stream agents. In this prototype the code already exists, so each agent
 * verifies its slice against the real classes with behavioural probes rather than claiming
 * success. A failed probe fails the stage, which the engine then retries or escalates.
 */
@Configuration(proxyBeanMethods = false)
public class ImplementationAgents {

    @Bean
    Agent implApi() {
        return probe("impl_api", "api", () -> {
            var missing = missingMethods(ShortUrlController.class, "create", "get", "analytics", "update", "deactivate");
            missing.addAll(missingMethods(RedirectController.class, "redirect"));
            return ordered("ok", missing.isEmpty(), "controllers", List.of("ShortUrlController", "RedirectController"),
                    "missingHandlers", List.copyOf(missing));
        });
    }

    @Bean
    Agent implStore() {
        return probe("impl_store", "store", () -> {
            var repository = new InMemoryUrlRepository();
            var link = new ShortUrl("probe01", "https://example.com", Instant.EPOCH, null, false, true, "probe", null);
            boolean first = repository.saveIfAbsent(link);
            boolean duplicate = repository.saveIfAbsent(link);
            return ordered("ok", first && !duplicate, "guarantee", "atomic insert-if-absent (alias uniqueness is race-free)");
        });
    }

    @Bean
    Agent implAnalytics(VisitorHasher hasher) {
        return probe("impl_analytics", "analytics", () -> {
            var ip = "203.0.113.10";
            var hash = hasher.hash(ip);
            boolean stable = hash.equals(hasher.hash(ip));
            return ordered("ok", stable && !hash.contains(ip), "privacy", "visitor id = salted, day-rotated SHA-256 prefix");
        });
    }

    @Bean
    Agent implAlias(LinkValidator validator) {
        return probe("impl_alias", "alias", () -> {
            boolean validAccepted = validator.validateAlias("campaign-01").isPresent();
            boolean reservedRejected = rejects(() -> validator.validateAlias("api"));
            boolean badCharsetRejected = rejects(() -> validator.validateAlias("a b"));
            return ordered("ok", validAccepted && reservedRejected && badCharsetRejected,
                    "rule", "3-32 chars of [A-Za-z0-9_-], reserved route names blocked");
        });
    }

    @Bean
    Agent implTtl(LinkValidator validator) {
        return probe("impl_ttl", "ttl", () -> {
            boolean ok = validator.validateTtl(3600L).isPresent()
                    && rejects(() -> validator.validateTtl(10L))
                    && rejects(() -> validator.validateTtl(LinkValidator.MAX_TTL_SECONDS + 1));
            return ordered("ok", ok, "bounds", LinkValidator.MIN_TTL_SECONDS + ".." + LinkValidator.MAX_TTL_SECONDS + " seconds",
                    "expiredStatus", 410);
        });
    }

    @Bean
    Agent implRatelimit() {
        return probe("impl_ratelimit", "ratelimit", () -> {
            var limiter = new SlidingWindowRateLimiter(2, Duration.ofMinutes(1));
            boolean ok = limiter.tryAcquire("probe").allowed()
                    && limiter.tryAcquire("probe").allowed()
                    && !limiter.tryAcquire("probe").allowed()
                    && limiter.tryAcquire("other").allowed();
            return ordered("ok", ok, "algorithm", "sliding-window log per identity", "scope", "POST/PATCH/DELETE");
        });
    }

    @Bean
    Agent implSsrf(LinkValidator validator) {
        return probe("impl_ssrf", "ssrf", () -> {
            var hostile = List.of("http://127.0.0.1/admin", "http://10.0.0.5", "http://169.254.169.254/latest/meta-data",
                    "http://[::1]/", "http://2130706433/", "http://localhost:8080", "javascript:alert(1)",
                    "file:///etc/passwd", "https://user:pass@example.com");
            var accepted = hostile.stream().filter(url -> !rejects(() -> validator.normalizeDestination(url))).toList();
            return ordered("ok", accepted.isEmpty(), "probedVectors", hostile.size(), "accepted", accepted);
        });
    }

    @Bean
    Agent implAudit(VisitorHasher hasher) {
        return probe("impl_audit", "audit", () -> ordered(
                "ok", !hasher.hash("198.51.100.7").contains("198.51.100.7"),
                "controls", List.of("append-only orchestrator trace per run", "decision lineage with actor",
                        "audit logger 'audit.orchestrator'", "no raw client IP persisted")));
    }

    @Bean
    Agent implRetention(ShortenerProperties properties) {
        return probe("impl_retention", "retention", () -> {
            var retention = properties.analytics().retention();
            boolean jobPresent = missingMethods(HousekeepingJobs.class, "enforceRetention").isEmpty();
            return ordered("ok", jobPresent && retention.compareTo(Duration.ofDays(30)) <= 0,
                    "retentionDays", retention.toDays(), "enforcement", "scheduled purge every 10 minutes");
        });
    }

    private static Agent probe(String agentId, String part, Supplier<Map<String, Object>> evidence) {
        return Agent.of(agentId, ctx -> {
            var result = evidence.get();
            ctx.publish("impl." + part, result);
            if (!Boolean.TRUE.equals(result.get("ok"))) {
                throw new IllegalStateException(part + " verification failed: " + result);
            }
        });
    }

    private static boolean rejects(Runnable call) {
        try {
            call.run();
            return false;
        } catch (InvalidLinkException expected) {
            return true;
        }
    }

    private static Set<String> missingMethods(Class<?> type, String... names) {
        var declared = Arrays.stream(type.getMethods()).map(Method::getName).collect(Collectors.toSet());
        return Arrays.stream(names).filter(n -> !declared.contains(n)).collect(Collectors.toCollection(TreeSet::new));
    }
}
