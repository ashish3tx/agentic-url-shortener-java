package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.orchestrator.model.StageContext;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.agentic.shortener.orchestrator.agent.Artifacts.ordered;
import static com.agentic.shortener.orchestrator.agent.Artifacts.strings;

@Component
public class DocumentationAgent implements Agent {

    @Override
    public String id() {
        return "documentation";
    }

    @Override
    public void execute(StageContext ctx) {
        var requirements = ctx.requireArtifact("requirements");
        var docs = ordered(
                "openApi", "/v3/api-docs",
                "swaggerUi", "/swagger-ui.html",
                "files", List.of("README.md", "ARCHITECTURE.md", "docs/scenarios.md", "ENGINEERING_SUMMARY.md"),
                "acceptanceCriteria", strings(requirements, "acceptanceCriteria"),
                "assumptions", strings(requirements, "assumptions"),
                "outOfScope", strings(requirements, "outOfScope"));
        switch (ctx.scenario()) {
            case BROWNFIELD ->
                    docs.put("changelog", ctx.artifact("impact").map(i -> i.get("apiDelta")).orElse(List.of()));
            case AMBIGUOUS -> {
                docs.put("deferredQuestions", strings(requirements, "openQuestions"));
                docs.put("residualRisks", List.of(
                        "DNS rebinding: hostnames are not resolved at write time",
                        "No SSO; identity is API key or client IP",
                        "In-memory stores are single-node"));
            }
            case GREENFIELD ->
                    docs.put("runbook", List.of("GET /actuator/health/liveness", "GET /actuator/health/readiness"));
        }
        ctx.publish("documentation", docs);
    }
}
