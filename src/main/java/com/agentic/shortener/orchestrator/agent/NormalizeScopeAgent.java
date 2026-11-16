package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.orchestrator.model.StageContext;
import org.springframework.stereotype.Component;

import static com.agentic.shortener.orchestrator.agent.Artifacts.ordered;
import static com.agentic.shortener.orchestrator.agent.Artifacts.strings;

/**
 * Packages the proposed interpretation for the human checkpoint; it never accepts it itself.
 */
@Component
public class NormalizeScopeAgent implements Agent {

    @Override
    public String id() {
        return "normalize_scope";
    }

    @Override
    public void execute(StageContext ctx) {
        var requirements = ctx.requireArtifact("requirements");
        ctx.publish("normalized_scope", ordered(
                "proposedAssumptions", strings(requirements, "assumptions"),
                "deferredQuestions", strings(requirements, "openQuestions"),
                "outOfScope", strings(requirements, "outOfScope"),
                "rationale", "The request is not a specification. Bounding it lets implementation proceed under "
                        + "governance instead of the agent inventing SSO, tenancy or a regulatory programme."));
    }
}
