package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.orchestrator.engine.OrchestratorEngine;
import com.agentic.shortener.orchestrator.model.ArtifactInfo;
import com.agentic.shortener.orchestrator.model.Scenario;
import com.agentic.shortener.orchestrator.model.StageContext;
import com.agentic.shortener.url.ratelimit.SlidingWindowRateLimiter;
import com.agentic.shortener.url.repository.UrlRepository;
import com.agentic.shortener.url.service.LinkValidator;
import com.agentic.shortener.url.service.ShortenerService;
import com.agentic.shortener.url.web.RedirectController;
import com.agentic.shortener.url.web.ShortUrlController;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.agentic.shortener.orchestrator.agent.Artifacts.ordered;
import static com.agentic.shortener.orchestrator.agent.Artifacts.strings;

@Component
public class ArchitectureAgent implements Agent {

    @Override
    public String id() {
        return "architecture";
    }

    @Override
    public void execute(StageContext ctx) {
        var requirements = ctx.requireArtifact("requirements");
        ctx.publish("architecture", ordered(
                "components", List.of(
                        component(ShortUrlController.class, "REST contract for link management"),
                        component(RedirectController.class, "hot read path: 302 + click capture"),
                        component(ShortenerService.class, "domain logic: codes, aliases, TTL, analytics"),
                        component(LinkValidator.class, "input rules and SSRF guardrail"),
                        component(UrlRepository.class, "persistence port (in-memory adapter)"),
                        component(SlidingWindowRateLimiter.class, "write-path abuse control"),
                        component(OrchestratorEngine.class, "SDLC control plane")),
                "dataModel", List.of("ShortUrl", "ClickEvent", "Run", "StageSpec", "DecisionRecord", "Approval"),
                "adrs", adrs(ctx.scenario(), ctx),
                "designedFor", ordered(
                        "acceptanceCriteria", strings(requirements, "acceptanceCriteria"),
                        "requirementsSha256", ctx.lineage("requirements").map(ArtifactInfo::sha256).orElse("n/a")),
                "controlFlow", "Client -> Controller -> ShortenerService -> UrlRepository; orchestrator governs changes around it"));
    }

    private static Map<String, Object> component(Class<?> type, String role) {
        return ordered("name", type.getSimpleName(), "type", type.getName(), "role", role);
    }

    private static List<Map<String, Object>> adrs(Scenario scenario, StageContext ctx) {
        var adrs = new ArrayList<Map<String, Object>>(List.of(
                ordered("id", "ADR-1", "title", "Repository port with in-memory adapter",
                        "decision", "ConcurrentHashMap behind UrlRepository so the prototype runs with zero infrastructure.",
                        "consequences", "Single node, not durable; swap in a JPA or Redis adapter without touching the service."),
                ordered("id", "ADR-2", "title", "Orchestrator is a gated DAG, not a linear chain",
                        "decision", "Stages declare dependencies, joins, gates, autonomy, retries, fallback, rollback and approvals.",
                        "consequences", "More control-plane code; enables parallel streams, re-planning and auditability.")));
        switch (scenario) {
            case BROWNFIELD -> adrs.add(ordered("id", "ADR-3", "title", "Additive API evolution",
                    "decision", "New fields and endpoints only; redirect contract and existing codes untouched.",
                    "consequences", "Old clients keep working; PATCH semantics documented in the OpenAPI contract."));
            case AMBIGUOUS ->
                    adrs.add(ordered("id", "ADR-3", "title", "Bound 'enterprise-ready' to preventative controls",
                            "decision", "Implement SSRF guards, hashed analytics, retention purge, audit trail and four-eyes approvals.",
                            "consequences", "Not a full regulatory programme; accepted assumptions: "
                                    + ctx.artifact("normalized_scope").map(s -> s.get("proposedAssumptions")).orElse(List.of())));
            case GREENFIELD -> {
                // ADR-1 and ADR-2 cover the greenfield design.
            }
        }
        return adrs;
    }
}
