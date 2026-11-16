package com.agentic.shortener.orchestrator.agent;

import com.agentic.shortener.orchestrator.gate.Gate;
import com.agentic.shortener.orchestrator.model.StageContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;

import static com.agentic.shortener.orchestrator.agent.Artifacts.ordered;

/**
 * Release agent prepares the go/no-go evidence; only a human approval actually releases.
 */
@Configuration(proxyBeanMethods = false)
public class ReleaseAgents {

    @Bean
    Agent release() {
        return Agent.of("release", ctx -> {
            var tests = ctx.requireArtifact("tests");
            var warnings = new ArrayList<String>();
            if (Boolean.TRUE.equals(tests.get("degradedCoverage"))) {
                warnings.add("only the smoke suite passed (fallback); full contract coverage not demonstrated");
            }
            ctx.publish("release", ordered(
                    "checklist", ordered(
                            "testsGreen", tests.get("failed") instanceof Number n && n.intValue() == 0,
                            "docsPresent", ctx.artifact("documentation").isPresent(),
                            "implementationComplete", "ok".equals(ctx.artifact("implementation").map(i -> i.get("status")).orElse(null)),
                            "policyClean", ctx.policyClean()),
                    "warnings", warnings,
                    "decision", "GO_PENDING_HUMAN_APPROVAL",
                    "gate", Gate.RELEASE_READY.name()));
        });
    }

    @Bean
    Agent rollbackRelease() {
        return Agent.of("rollback_release", ReleaseAgents::rollback);
    }

    private static void rollback(StageContext ctx) {
        ctx.publish("release", ordered(
                "decision", "NO_GO",
                "rolledBack", true,
                "action", "release candidate withdrawn; previous version remains live"));
    }
}
