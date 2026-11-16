package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.orchestrator.model.StageContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static com.agentic.shortener.orchestrator.agent.Artifacts.ordered;

/**
 * Turns a raw brief into a normalized engineering problem. For an ambiguous brief it does not
 * invent answers: it lists open questions and proposes explicit assumptions for a human to accept.
 */
@Component
public class RequirementsAgent implements Agent {

    @Override
    public String id() {
        return "requirements";
    }

    @Override
    public void execute(StageContext ctx) {
        var scenario = ctx.scenario();
        var artifact = switch (scenario) {
            case GREENFIELD -> greenfield();
            case BROWNFIELD -> brownfield();
            case AMBIGUOUS -> ambiguous();
        };
        artifact.put("brief", ordered("title", scenario.title(), "text", scenario.brief(), "wellDefined", scenario.wellDefined()));
        ctx.publish("requirements", artifact);
    }

    private static Map<String, Object> greenfield() {
        return ordered(
                "problem", "Provide a reliable HTTP service that maps long URLs to short codes, redirects, and reports click analytics.",
                "acceptanceCriteria", List.of(
                        "POST /api/v1/urls creates a short link (201) with a unique code",
                        "GET /{code} redirects with 302 and records a click",
                        "Analytics expose total clicks, unique visitors, clicks by day and top referrers",
                        "Non-http(s), private, loopback and reserved destinations are rejected with 400",
                        "Idempotency-Key replays return the original link",
                        "Liveness and readiness probes are exposed"),
                "nonFunctional", List.of("p99 redirect latency < 50 ms in-process", "no raw IPs persisted", "stateless API layer"),
                "openQuestions", List.of(),
                "assumptions", List.of(
                        "In-memory persistence behind a repository port is acceptable for the prototype",
                        "Short codes are 7-char random base62 unless a custom alias is supplied"),
                "outOfScope", List.of("multi-region replication", "SSO", "custom domains"));
    }

    private static Map<String, Object> brownfield() {
        return ordered(
                "problem", "Extend the existing shortener with aliases, TTL, PATCH updates and rate limiting without regressing redirects.",
                "acceptanceCriteria", List.of(
                        "Custom aliases are unique and cannot shadow reserved paths",
                        "Expired links return 410 Gone",
                        "PATCH /api/v1/urls/{code} changes destination and/or TTL",
                        "Write APIs are rate-limited per API key or client IP with 429 + Retry-After",
                        "Existing GET /{code} contract stays 302"),
                "openQuestions", List.of(),
                "assumptions", List.of("Change is additive: no field renames or removed endpoints"),
                "outOfScope", List.of("billing", "team workspaces"));
    }

    private static Map<String, Object> ambiguous() {
        return ordered(
                "problem", "Raise the shortener to a bounded financial-services control baseline.",
                "acceptanceCriteria", List.of(
                        "Scheme and private-network (SSRF) controls are enforced on every destination",
                        "Click IPs are never stored raw",
                        "Click data is purged after the retention window",
                        "Every orchestrated change emits an audit trail",
                        "High-impact actions require human approval with separation of duties"),
                "ambiguities", List.of(
                        "Identity model (workforce SSO vs API keys) is unspecified",
                        "Retention period is unspecified",
                        "Regulatory overlay (SOX, GLBA, SEC 17a-4) is unspecified",
                        "Tenant isolation requirements are unspecified"),
                "openQuestions", List.of(
                        "Is workforce SSO required now, or is API-key identity sufficient?",
                        "What click-log retention window is required?",
                        "Must destinations be allow-listed per tenant?"),
                "assumptions", List.of(
                        "API-key / IP identity is sufficient; SSO is out of scope for this increment",
                        "Click events are retained for 30 days and purged by a scheduled job",
                        "Default-deny unsafe schemes and private destinations; no per-tenant allow-list yet",
                        "Production deploy requires a second person's approval (four-eyes)"),
                "outOfScope", List.of("SSO/SAML", "WAF", "HSM key custody", "multi-region failover"));
    }
}
